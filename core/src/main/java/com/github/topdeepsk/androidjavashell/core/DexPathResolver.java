package com.github.topdeepsk.androidjavashell.core;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 定位一个 {@link Class} 所属的 APK / dex 路径。
 *
 * <p>Android 上 {@code getProtectionDomain().getCodeSource()} 不可用，因此先解析类加载器
 * {@code toString()} 里的 {@code DexPathList}（公开信息），失败时再反射隐藏字段兜底。</p>
 */
public final class DexPathResolver {

    private static final String[] EMPTY = new String[0];

    private static final String TOKEN_FILE = "file \"";

    private static final String TOKEN_DIRECTORY = "directory \"";

    private DexPathResolver() {
    }

    public static String[] getDexPaths(Class<?> clazz) {
        if (clazz == null) {
            throw new JavaShellArgumentException("clazz must not be null");
        }
        var loader = clazz.getClassLoader();
        if (loader == null) {
            return EMPTY;
        }
        var paths = new LinkedHashSet<String>();
        collectFromLoaderText(loader.toString(), paths);
        if (paths.isEmpty()) {
            collectFromDexElements(loader, paths);
        }
        return paths.toArray(new String[0]);
    }

    private static void collectFromLoaderText(String text, Set<String> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        collectQuoted(text, TOKEN_FILE, out);
        collectQuoted(text, TOKEN_DIRECTORY, out);
    }

    private static void collectQuoted(String text, String token, Set<String> out) {
        int index = 0;
        while (true) {
            int tokenIndex = text.indexOf(token, index);
            if (tokenIndex < 0) {
                return;
            }
            int valueStart = tokenIndex + token.length();
            int valueEnd = text.indexOf('"', valueStart);
            if (valueEnd < 0) {
                return;
            }
            String value = text.substring(valueStart, valueEnd);
            if (!value.isEmpty()) {
                out.add(value);
            }
            index = valueEnd + 1;
        }
    }

    private static void collectFromDexElements(ClassLoader loader, Set<String> out) {
        try {
            var pathListField = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList");
            pathListField.setAccessible(true);
            var pathList = pathListField.get(loader);
            if (pathList == null) {
                return;
            }
            var elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            var elements = (Object[]) elementsField.get(pathList);
            if (elements == null) {
                return;
            }
            for (var element : elements) {
                if (element == null) {
                    continue;
                }
                try {
                    var pathField = element.getClass().getDeclaredField("path");
                    pathField.setAccessible(true);
                    var value = pathField.get(element);
                    if (value != null) {
                        out.add(value.toString());
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
