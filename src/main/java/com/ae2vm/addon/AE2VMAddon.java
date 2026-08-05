package com.ae2vm.addon;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * AE2 VM entry point for Minecraft 1.12.2.
 *
 * The mod only replaces the calculation stage. AE2 still owns job submission,
 * inventory commits, CPU task tracking, and requester callbacks.
 */
@Mod(
        modid = AE2VMAddon.MOD_ID,
        name = AE2VMAddon.NAME,
        version = AE2VMAddon.VERSION,
        dependencies = "required-after:appliedenergistics2;required-after:mixinbooter;"
)
public final class AE2VMAddon {
    public static final String MOD_ID = "ae2vm";
    public static final String NAME = "AE2 VM";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("AE2 VM {} loaded for AE2 Unofficial Extended Life", VERSION);
        LOGGER.info("Crafting calculations use the iterative bytecode engine when compatible");
    }
}
