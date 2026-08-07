package com.ae2.quickcalculation;

import com.ae2.quickcalculation.calculator.CraftingCalculator;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import com.ae2.quickcalculation.network.AE2QuickCalculationNetwork;
import com.ae2.quickcalculation.proxy.CommonProxy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** AE2 Quick Calculation entry point for Minecraft 1.12.2. */
@Mod(
        modid = AE2QuickCalculation.MOD_ID,
        name = AE2QuickCalculation.NAME,
        version = AE2QuickCalculation.VERSION,
        dependencies = "required-after:appliedenergistics2;required-after:mixinbooter;",
        // The calculation engine is server-authoritative. A client may omit
        // this mod and simply lose the optional status toast.
        acceptableRemoteVersions = "*"
)
public final class AE2QuickCalculation {
    public static final String MOD_ID = "ae2_quick_calculation";
    public static final String NAME = "AE2 Quick Calculation";
    public static final String VERSION = "1.0.0";
    public static final String TOAST_TITLE = MOD_ID + ".toast.title";
    public static final String TOAST_SEPARATOR = MOD_ID + ".toast.separator";
    public static final String TOAST_ELAPSED = MOD_ID + ".toast.elapsed";
    public static final String TOAST_OPTIMIZED = MOD_ID + ".toast.optimized";
    public static final String TOAST_OPTIMIZED_CYCLE = MOD_ID + ".toast.optimized.cycle";
    public static final String TOAST_FALLBACK = MOD_ID + ".toast.fallback";
    public static final String TOAST_FALLBACK_UNSUPPORTED =
            MOD_ID + ".toast.fallback.unsupported";
    public static final String TOAST_FALLBACK_SUBSTITUTION =
            MOD_ID + ".toast.fallback.substitution";
    public static final String TOAST_FALLBACK_CONTAINER =
            MOD_ID + ".toast.fallback.container";
    public static final String TOAST_FALLBACK_CYCLE =
            MOD_ID + ".toast.fallback.cycle";
    public static final String TOAST_FALLBACK_CYCLE_NO_SEED =
            MOD_ID + ".toast.fallback.cycle_no_seed";
    public static final String TOAST_FALLBACK_CYCLE_NEUTRAL =
            MOD_ID + ".toast.fallback.cycle_neutral";
    public static final String TOAST_FALLBACK_CYCLE_DISSIPATIVE =
            MOD_ID + ".toast.fallback.cycle_dissipative";
    public static final String TOAST_FALLBACK_CYCLE_COMPLEX =
            MOD_ID + ".toast.fallback.cycle_complex";
    public static final String TOAST_FALLBACK_CYCLE_EXTERNAL =
            MOD_ID + ".toast.fallback.cycle_external";
    public static final String TOAST_RUNTIME_FAILURE =
            MOD_ID + ".toast.runtime_failure";
    public static final String TOAST_QUANTITY_LIMIT = MOD_ID + ".toast.quantity_limit";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @SidedProxy(
            clientSide = "com.ae2.quickcalculation.proxy.ClientProxy",
            serverSide = "com.ae2.quickcalculation.proxy.CommonProxy")
    public static CommonProxy PROXY;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        AE2QuickCalculationNetwork.init();
        PROXY.preInit();
        LOGGER.info("{} {} loaded for AE2 Unofficial Extended Life", NAME, VERSION);
        LOGGER.info("Compatible crafting calculations use the direct calculation engine");
    }

    public static String statusFor(CraftingCalculator.FallbackReason reason) {
        if (reason == null) {
            return TOAST_FALLBACK;
        }
        switch (reason) {
            case SUBSTITUTION_CONTAINER:
                return TOAST_FALLBACK_SUBSTITUTION;
            case NON_LINEAR_CONTAINER:
            case DAMAGEABLE_ALLOCATION:
                return TOAST_FALLBACK_CONTAINER;
            case CYCLE_NO_SEED:
                return TOAST_FALLBACK_CYCLE_NO_SEED;
            case CYCLE_NEUTRAL:
                return TOAST_FALLBACK_CYCLE_NEUTRAL;
            case CYCLE_DISSIPATIVE:
                return TOAST_FALLBACK_CYCLE_DISSIPATIVE;
            case CYCLE_TOO_COMPLEX:
                return TOAST_FALLBACK_CYCLE_COMPLEX;
            case CYCLE_EXTERNAL_RECURSION:
                return TOAST_FALLBACK_CYCLE_EXTERNAL;
            case CYCLE_NOT_PROVABLE:
                return TOAST_FALLBACK_CYCLE;
            case INVALID_OUTPUT:
            case UNSUPPORTED_PATTERN:
            default:
                return TOAST_FALLBACK_UNSUPPORTED;
        }
    }
}
