package com.gamepathics.pluginanalytics;

import com.gamepathics.pluginanalytics.core.Engine;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Level;

/**
 * The consent question in a player's game: Minecraft's own Yes/No screen, opened over the title screen the
 * first time the game reaches it, until the player answers. Built by reflection like the rest of the SDK (it
 * doesn't compile against Minecraft): intermediary names up to 1.21.x, Mojang names from 26.1. If the screen
 * can't be built, nothing is sent and the log says how to allow it in the config.
 */
final class ConsentScreen {

    /** One question at a time across every mod using PluginAnalytics (each has its own relocated copy). */
    private static final String OPEN = "pluginanalytics.consent.open";

    private ConsentScreen() {}

    static void ask(String modName, Engine engine) {
        Thread t = new Thread(() -> waitAndShow(modName, engine), "PluginAnalytics consent");
        t.setDaemon(true);
        t.start();
    }

    private static void waitAndShow(String modName, Engine engine) {
        try {
            ClassLoader loader = ConsentScreen.class.getClassLoader();
            Class<?> minecraft = cls(loader, "net.minecraft.class_310", "net.minecraft.client.MinecraftClient", "net.minecraft.client.Minecraft");
            Class<?> title = cls(loader, "net.minecraft.class_442", "net.minecraft.client.gui.screen.TitleScreen", "net.minecraft.client.gui.screens.TitleScreen");
            Class<?> screenType = cls(loader, "net.minecraft.class_437", "net.minecraft.client.gui.screen.Screen", "net.minecraft.client.gui.screens.Screen");
            Class<?> confirm = cls(loader, "net.minecraft.class_410", "net.minecraft.client.gui.screen.ConfirmScreen", "net.minecraft.client.gui.screens.ConfirmScreen");
            Class<?> text = cls(loader, "net.minecraft.class_2561", "net.minecraft.text.Text", "net.minecraft.network.chat.Component");
            Method getInstance = method(minecraft, "net.minecraft.class_310", "method_1551", "()Lnet/minecraft/class_310;", "getInstance", "getInstance");
            Field screenField = field(minecraft, "net.minecraft.class_310", "field_1755", "Lnet/minecraft/class_437;", "currentScreen", "screen");
            Method setScreen = method(minecraft, "net.minecraft.class_310", "method_1507", "(Lnet/minecraft/class_437;)V", "setScreen", "setScreen", screenType);
            Method literal = method(text, "net.minecraft.class_2561", "method_43470", "(Ljava/lang/String;)Lnet/minecraft/class_5250;", "literal", "literal", String.class);
            Constructor<?> make = null;
            for (Constructor<?> c : confirm.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 3 && p[0].isInterface() && p[1].isAssignableFrom(text) && p[2].isAssignableFrom(text)) make = c;
            }
            if (make == null) throw new IllegalStateException("no ConfirmScreen constructor");
            final Constructor<?> ctor = make;

            // Wait for the title screen (and for another mod's question to be answered), up to 30 minutes.
            for (int i = 0; i < 1800 && engine.consentPending(); i++) {
                Thread.sleep(1000);
                Object mc = getInstance.invoke(null);
                if (mc == null) continue;
                Object current = screenField.get(mc);
                if (current == null || !title.isInstance(current) || System.getProperty(OPEN) != null) continue;
                System.setProperty(OPEN, modName);
                Object prompt = Proxy.newProxyInstance(ctor.getParameterTypes()[0].getClassLoader(), new Class<?>[] {ctor.getParameterTypes()[0]}, (self, m, args) -> {
                    if (m.getDeclaringClass() == Object.class) {
                        return m.getName().equals("equals") ? self == args[0] : m.getName().equals("hashCode") ? System.identityHashCode(self) : "ConsentPrompt";
                    }
                    if (m.getName().equals("accept") && args != null && args.length == 1 && args[0] instanceof Boolean) {
                        engine.consent((Boolean) args[0]);
                        System.clearProperty(OPEN);
                        setScreen.invoke(mc, current); // back to the title screen
                    }
                    return null;
                });
                Object question = ctor.newInstance(prompt,
                    literal.invoke(null, modName + ": anonymous usage statistics"),
                    literal.invoke(null, modName + " would like to send anonymous usage statistics to its developer: your game, mod and loader "
                        + "versions and how the mod's features are used. Nothing identifies you: no name, account, IP, chat or world data. "
                        + "You can change your answer in config/pluginanalytics.yml. Allow it?"));
                ((java.util.concurrent.Executor) mc).execute(() -> {
                    try {
                        setScreen.invoke(mc, question);
                    } catch (Throwable t) {
                        System.clearProperty(OPEN);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            System.clearProperty(OPEN);
            engine.log(Level.INFO, modName + " couldn't show its consent question (" + t.getClass().getSimpleName()
                + "), so it sends nothing. To allow anonymous usage statistics, set consent-" + engine.consentId() + ": true in config/pluginanalytics.yml.");
        }
    }

    private static Class<?> cls(ClassLoader loader, String intermediary, String yarn, String mojang) throws ClassNotFoundException {
        for (String n : new String[] {mapped(intermediary), intermediary, yarn, mojang}) {
            if (n == null) continue;
            try {
                return Class.forName(n, false, loader);
            } catch (ClassNotFoundException ignored) {
                // Next name.
            }
        }
        throw new ClassNotFoundException(mojang);
    }

    private static String mapped(String intermediaryClass) {
        try {
            MappingResolver r = FabricLoader.getInstance().getMappingResolver();
            return r.getNamespaces().contains("intermediary") ? r.mapClassName("intermediary", intermediaryClass) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method method(Class<?> on, String owner, String intermediary, String desc, String yarn, String mojang, Class<?>... params) throws NoSuchMethodException {
        String mappedName = null;
        try {
            MappingResolver r = FabricLoader.getInstance().getMappingResolver();
            if (r.getNamespaces().contains("intermediary")) mappedName = r.mapMethodName("intermediary", owner, intermediary, desc);
        } catch (Throwable ignored) {
            // Unobfuscated Minecraft.
        }
        for (String n : new String[] {mappedName, intermediary, yarn, mojang}) {
            if (n == null) continue;
            try {
                return on.getMethod(n, params);
            } catch (NoSuchMethodException ignored) {
                // Next name.
            }
        }
        throw new NoSuchMethodException(mojang);
    }

    private static Field field(Class<?> on, String owner, String intermediary, String desc, String yarn, String mojang) throws NoSuchFieldException {
        String mappedName = null;
        try {
            MappingResolver r = FabricLoader.getInstance().getMappingResolver();
            if (r.getNamespaces().contains("intermediary")) mappedName = r.mapFieldName("intermediary", owner, intermediary, desc);
        } catch (Throwable ignored) {
            // Unobfuscated Minecraft.
        }
        for (String n : new String[] {mappedName, intermediary, yarn, mojang}) {
            if (n == null) continue;
            try {
                return on.getField(n);
            } catch (NoSuchFieldException ignored) {
                // Next name.
            }
        }
        throw new NoSuchFieldException(mojang);
    }
}
