package com.gamepathics.pluginanalytics;

import com.gamepathics.pluginanalytics.core.Engine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Level;

/**
 * The consent question in a player's game: Minecraft's own Yes/No screen, opened over the title screen the
 * first time the game reaches it, until the player answers. Built by reflection like the rest of the SDK (it
 * doesn't compile against Minecraft), with the Mojang names NeoForge runs with. If the screen can't be built,
 * nothing is sent and the log says how to allow it in the config.
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
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> title = Class.forName("net.minecraft.client.gui.screens.TitleScreen", false, loader);
            Class<?> screenType = Class.forName("net.minecraft.client.gui.screens.Screen", false, loader);
            Class<?> confirm = Class.forName("net.minecraft.client.gui.screens.ConfirmScreen", false, loader);
            Class<?> text = Class.forName("net.minecraft.network.chat.Component", false, loader);
            Method getInstance = minecraft.getMethod("getInstance");
            Field screenField = minecraft.getField("screen");
            Method setScreen = minecraft.getMethod("setScreen", screenType);
            Method literal = text.getMethod("literal", String.class);
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
}
