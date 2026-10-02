// Copyright (c) 2026 DDgamer. All rights reserved.
package com.deeppixel;

import java.lang.reflect.InvocationTargetException;

/**
 * Starts Paper after making this Java report a normal release version.
 * The Java build used on phones calls itself "21.0.3-internal", and Paper refuses to start on such builds.
 */
public final class Launcher {
    public static void main(String[] args) throws Throwable {
        try {
            String v = "21.0.3+9-LTS";
            System.setProperty("java.version", "21.0.3");
            System.setProperty("java.runtime.version", v);
            java.lang.reflect.Field f = Runtime.class.getDeclaredField("version");
            f.setAccessible(true);
            f.set(null, Runtime.Version.parse(v));
        } catch (Throwable t) {
            System.err.println("[DeepPixel] Could not adjust the Java version: " + t);
        }
        try {
            Class.forName("io.papermc.paperclip.Main").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
