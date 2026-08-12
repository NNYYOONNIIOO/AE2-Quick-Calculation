package appeng.crafting;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Supplies the native AE2 crafting-tree graph to optional tree viewers.
 *
 * <p>The quick calculator deliberately keeps execution independent from the
 * native recursive tree. AE2CT, however, reads that tree for its preview.
 * This bridge constructs only the display graph, and only when AE2CT is
 * installed, so normal calculations do not pay this cost.</p>
 */
final class CraftingTreeCompatibility {
    private static final String AE2CT_MARKER =
            "github.kasuminova.ae2ctl.common.util.CraftingTreeProcessUtil";

    private static final Set<CraftingTreeNode> PREPARED =
            Collections.newSetFromMap(new WeakHashMap<CraftingTreeNode, Boolean>());

    private static volatile boolean ae2ctChecked;
    private static volatile boolean ae2ctInstalled;
    private static volatile boolean fieldsChecked;
    private static volatile Field nodeProcessesField;
    private static volatile Field processNodesField;

    private CraftingTreeCompatibility() {
    }

    static void populate(final CraftingTreeNode root) {
        if (root == null || !isAe2ctInstalled()) {
            return;
        }

        synchronized (PREPARED) {
            if (PREPARED.contains(root)) {
                return;
            }
            PREPARED.add(root);
        }

        if (!resolveFields()) {
            return;
        }

        final ArrayDeque<CraftingTreeNode> pending = new ArrayDeque<>();
        final Set<CraftingTreeNode> visited =
                Collections.newSetFromMap(new IdentityHashMap<CraftingTreeNode, Boolean>());
        pending.add(root);

        while (!pending.isEmpty()) {
            final CraftingTreeNode node = pending.removeFirst();
            if (!visited.add(node)) {
                continue;
            }

            try {
                node.addNode();
            } catch (final Throwable ignored) {
                continue;
            }

            final ArrayList<CraftingTreeProcess> processes = getProcesses(node);
            if (processes == null) {
                continue;
            }

            for (final CraftingTreeProcess process : processes) {
                try {
                    process.addProcess();
                } catch (final Throwable ignored) {
                    continue;
                }

                final Map<?, ?> children = getChildren(process);
                if (children == null) {
                    continue;
                }
                for (final Object child : children.keySet()) {
                    if (child instanceof CraftingTreeNode) {
                        pending.addLast((CraftingTreeNode) child);
                    }
                }
            }
        }
    }

    private static boolean isAe2ctInstalled() {
        if (ae2ctChecked) {
            return ae2ctInstalled;
        }
        synchronized (CraftingTreeCompatibility.class) {
            if (!ae2ctChecked) {
                try {
                    Class.forName(AE2CT_MARKER, false, CraftingTreeCompatibility.class.getClassLoader());
                    ae2ctInstalled = true;
                } catch (final Throwable ignored) {
                    ae2ctInstalled = false;
                }
                ae2ctChecked = true;
            }
        }
        return ae2ctInstalled;
    }

    private static boolean resolveFields() {
        if (fieldsChecked) {
            return nodeProcessesField != null && processNodesField != null;
        }
        synchronized (CraftingTreeCompatibility.class) {
            if (!fieldsChecked) {
                try {
                    nodeProcessesField = CraftingTreeNode.class.getDeclaredField("nodes");
                    processNodesField = CraftingTreeProcess.class.getDeclaredField("nodes");
                    nodeProcessesField.setAccessible(true);
                    processNodesField.setAccessible(true);
                } catch (final Throwable ignored) {
                    nodeProcessesField = null;
                    processNodesField = null;
                }
                fieldsChecked = true;
            }
        }
        return nodeProcessesField != null && processNodesField != null;
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<CraftingTreeProcess> getProcesses(final CraftingTreeNode node) {
        try {
            return (ArrayList<CraftingTreeProcess>) nodeProcessesField.get(node);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> getChildren(final CraftingTreeProcess process) {
        try {
            return (Map<?, ?>) processNodesField.get(process);
        } catch (final Throwable ignored) {
            return null;
        }
    }
}
