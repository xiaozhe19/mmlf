package dev.mmlf.launcher.runtime;

import java.io.IOException;
import java.net.URL;
import java.util.*;

/**
 * Parent of a started runtime's class loaders: the JDK, and nothing of the host. The platform loader alone is not
 * enough: the JDK's built-in loaders share one package-to-module map, so asking the platform loader for a package of
 * a named module the host put on its module path (NeoForge's ASM and securejarhandler, defined to the application
 * loader) answers with the host's class. Those packages are hidden here.
 */
final class JdkOnlyLoader extends ClassLoader {
    static { registerAsParallelCapable(); }
    private static final JdkOnlyLoader INSTANCE=new JdkOnlyLoader();
    private final Set<String> hidden=new HashSet<>();

    private JdkOnlyLoader() {
        super("mmlf-jdk",ClassLoader.getPlatformClassLoader());
        for(Module m:ModuleLayer.boot().modules()) {
            ClassLoader l=m.getClassLoader();
            if(l!=null && l!=ClassLoader.getPlatformClassLoader()) hidden.addAll(m.getPackages());
        }
    }
    static JdkOnlyLoader get() { return INSTANCE; }
    /** Packages of the host's own boot-layer modules, which a started runtime must load from its own class path. */
    Set<String> hiddenPackages() { return Collections.unmodifiableSet(hidden); }

    private boolean hides(String name) {
        int dot=name.lastIndexOf('.');
        return dot>0 && hidden.contains(name.substring(0,dot));
    }
    @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
        if(hides(name)) throw new ClassNotFoundException(name);
        return super.loadClass(name,resolve);
    }
    private boolean hidesResource(String name) {
        int slash=name.lastIndexOf('/');
        return slash>0 && hidden.contains(name.substring(0,slash).replace('/','.'));
    }
    @Override public URL getResource(String name) { return hidesResource(name)?null:super.getResource(name); }
    @Override public Enumeration<URL> getResources(String name) throws IOException { return hidesResource(name)?Collections.emptyEnumeration():super.getResources(name); }
}
