package codechicken.lib.asm;

import java.util.*;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.objectweb.asm.tree.ClassNode;

import cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;

/**
 * This is added as a class transformer if CodeChickenCore is installed. Adding it as a class transformer will speed
 * evaluation up slightly by automatically caching superclasses when they are first loaded.
 */
public class ClassHeirachyManager implements IClassTransformer {

    static {
        ASMInit.init();
    }

    public static class SuperCache {

        String superclass;
        Set<String> parents = new TreeSet<>();
        private boolean flattened;

        public void add(String parent) {
            parents.add(parent);
        }

        public void flatten() {
            if (flattened) return;

            for (String s : new TreeSet<>(parents)) {
                SuperCache c = declareClass(s);
                if (c != null) {
                    c.flatten();
                    parents.addAll(c.parents);
                }
            }
            flattened = true;
        }
    }

    public static Map<String, SuperCache> superclasses = new HashMap<>();
    private static final LaunchClassLoader cl = Launch.classLoader;

    public static String toKey(String name) {
        if (ObfMapping.obfuscated)
            name = FMLDeobfuscatingRemapper.INSTANCE.map(name.replace('.', '/')).replace('/', '.');
        return name;
    }

    public static String unKey(String name) {
        if (ObfMapping.obfuscated)
            name = FMLDeobfuscatingRemapper.INSTANCE.unmap(name.replace('.', '/')).replace('/', '.');
        return name;
    }

    /**
     * @param name       The class in question
     * @param superclass The class being extended
     * @return true if clazz extends, either directly or indirectly, superclass.
     */
    public static boolean classExtends(String name, String superclass) {
        name = toKey(name);
        superclass = toKey(superclass);

        if (name.equals(superclass)) return true;

        SuperCache cache = superclasses.get(name);
        if (cache == null) { // just can't handle this
            cache = declareClass(name);
            if (cache == null) return false;
        }

        cache.flatten();
        return cache.parents.contains(superclass);
    }

    private static SuperCache declareClass(String name) {
        name = toKey(name);
        SuperCache cache = superclasses.get(name);

        if (cache != null) return cache;

        try {
            byte[] bytes = cl.getClassBytes(unKey(name));
            if (bytes != null) cache = declareASM(bytes);
        } catch (Exception ignored) {}

        if (cache != null) return cache;

        try {
            cache = declareReflection(name);
        } catch (ClassNotFoundException ignored) {}

        return cache;
    }

    private static SuperCache declareReflection(String name) throws ClassNotFoundException {
        Class<?> aclass = Class.forName(name);
        SuperCache cache = new SuperCache();
        if (aclass.isInterface()) cache.superclass = "java.lang.Object";
        else if (name.equals("java.lang.Object")) return cache;
        else cache.superclass = toKey(aclass.getSuperclass().getName());

        cache.add(cache.superclass);
        for (Class<?> iclass : aclass.getInterfaces()) cache.add(toKey(iclass.getName()));

        return cache;
    }

    private static SuperCache declareASM(byte[] bytes) {
        ClassNode node = ASMHelper.createClassNode(bytes);
        toKey(node.name);
        SuperCache cache = new SuperCache();
        cache.superclass = toKey(node.superName.replace('/', '.'));
        cache.add(cache.superclass);
        for (String iclass : node.interfaces) cache.add(toKey(iclass.replace('/', '.')));

        return cache;
    }

    @Override
    public byte[] transform(String name, String tname, byte[] bytes) {
        if (bytes == null) return new byte[0];

        if (!superclasses.containsKey(tname)) declareASM(bytes);

        return bytes;
    }

    public static String getSuperClass(String name, boolean runtime) {
        name = toKey(name);
        SuperCache cache = declareClass(name);
        if (cache == null) return "java.lang.Object";

        cache.flatten();
        String s = cache.superclass;
        if (!runtime) s = FMLDeobfuscatingRemapper.INSTANCE.unmap(s);
        return s;
    }
}
