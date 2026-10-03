// Copyright (c) 2026 DDgamer. All rights reserved.
package com.deeppixel;

import java.lang.reflect.InvocationTargetException;
import java.util.stream.Collectors;

/**
 * Starts Paper after making this Java report a normal release version.
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
        try {
            Class.forName("io.papermc.paperclip.Main").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
