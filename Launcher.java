// Copyright (c) 2026 DDgamer. All rights reserved.
package com.deeppixel;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Starts the Paper jar given as the first argument, after making this Java report a normal release version.
 * Java builds made for phones call themselves e.g. "21.0.3-internal", and Paper refuses to start on such builds.
 * The real version numbers are kept (Java 17, 21, 25...), only the pre-release tag is removed.
 */
public final class Launcher {
    public static void main(String[] args) throws Throwable {
        try {
            Runtime.Version real = Runtime.version();
            String nums = real.version().stream().map(String::valueOf).collect(Collectors.joining("."));
            String v = nums + "+" + real.build().orElse(1);
            System.setProperty("java.version", nums);
            System.setProperty("java.runtime.version", v);
            java.lang.reflect.Field f = Runtime.class.getDeclaredField("version");
            f.setAccessible(true);
            f.set(null, Runtime.Version.parse(v));
        } catch (Throwable t) {
            System.err.println("[DeepPixel] Could not adjust the Java version: " + t);
        }

        File jar = new File(args[0]).getAbsoluteFile();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (!jar.isFile() || jar.length() == 0) throw new IllegalStateException("The server jar is missing or empty: " + jar + ". Reinstall it in Server Settings (Apply).");
        String main;
        try (JarFile jf = new JarFile(jar)) {
            main = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes().getValue("Main-Class");
        } catch (Exception e) {
            throw new IllegalStateException("The server jar is damaged (" + e + "). Reinstall it in Server Settings (Apply).");
        }
        if (main == null) throw new IllegalStateException("The server jar has no start class: " + jar);
        System.out.println("[DeepPixel] Starting " + main + " from " + jar.getName() + " (" + (jar.length() / 1024) + " KB)");

        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, Launcher.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class.forName(main, true, loader).getMethod("main", String[].class).invoke(null, (Object) rest);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
