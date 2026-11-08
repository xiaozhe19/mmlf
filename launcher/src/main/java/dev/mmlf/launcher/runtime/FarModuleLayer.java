package dev.mmlf.launcher.runtime;

import java.io.IOException;
import java.lang.module.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The module path of a runtime whose launch puts its bootstrap on the JVM's module path (NeoForge: bootstraplauncher,
 * securejarhandler, JarJarFileSystems, ASM). In a JVM someone else started those modules cannot join the boot layer,
 * so they get a layer of their own above it. BootstrapLauncher builds the game's layers on top of
 * {@code ModuleLayer.boot()}; in this one class that call is redirected to {@link #boot()}, which answers this layer,
 * so the native bootstrap runs unchanged on top of the modules it expects below it.
 */
public final class FarModuleLayer {
    private static volatile ModuleLayer current;
    private static final String BOOTSTRAP="cpw.mods.bootstraplauncher.BootstrapLauncher";
    private static final String MODULE_CLASS_LOADER="cpw.mods.cl.ModuleClassLoader";
    private static volatile ClassLoader platform;
    private FarModuleLayer() {}

    /** Called instead of {@code ModuleLayer.boot()} by the patched BootstrapLauncher. */
    public static ModuleLayer boot() { ModuleLayer layer=current; return layer!=null?layer:ModuleLayer.boot(); }
    /**
     * Called instead of {@code ClassLoader.getPlatformClassLoader()} by securejarhandler's ModuleClassLoader, whose
     * fallback that is. With the bootstrap modules in the boot layer, the JDK's platform loader also answers their
     * packages (the built-in loaders share one package map); some game-layer modules rely on that without declaring
     * the read (MixinExtras on ASM). This loader answers the same: the JDK, plus this layer's packages.
     */
    public static ClassLoader getPlatformClassLoader() { ClassLoader l=platform; return l!=null?l:ClassLoader.getPlatformClassLoader(); }

    static synchronized ModuleLayer create(String name,List<Path> modulePath) {
        if(current!=null) throw new IllegalStateException("a module-path runtime is already running in this JVM");
        ModuleFinder finder=ModuleFinder.of(modulePath.toArray(Path[]::new));
        Set<String> roots=new HashSet<>();
        for(ModuleReference r:finder.findAll()) roots.add(r.descriptor().name());
        Configuration cf=ModuleLayer.boot().configuration().resolveAndBind(finder,ModuleFinder.of(),roots);
        Loader loader=new Loader(name,cf);
        ModuleLayer.Controller controller=ModuleLayer.defineModules(cf,List.of(ModuleLayer.boot()),m->loader);
        ModuleLayer layer=controller.layer();
        // The patched bootstrap calls this class, which lives outside its module graph.
        layer.findModule("cpw.mods.bootstraplauncher").ifPresent(m->controller.addReads(m,FarModuleLayer.class.getModule()));
        layer.findModule("cpw.mods.securejarhandler").ifPresent(m->controller.addReads(m,FarModuleLayer.class.getModule()));
        current=layer;
        platform=loader;
        return layer;
    }
    static synchronized void release(ModuleLayer layer) { if(current==layer) { current=null; platform=null; } }

    /** One loader for the whole layer; it defines each module's classes and delegates everything else to the platform. */
    static final class Loader extends ClassLoader {
        static { registerAsParallelCapable(); }
        private final Map<String,ModuleReference> packages=new HashMap<>();
        private final Map<String,ModuleReader> readers=new ConcurrentHashMap<>();
        private final Map<ModuleReference,ProtectionDomain> domains=new ConcurrentHashMap<>();
        Loader(String name,Configuration cf) {
            super(name,JdkOnlyLoader.get());
            for(ResolvedModule m:cf.modules()) for(String p:m.reference().descriptor().packages()) packages.put(p,m.reference());
        }
        private ModuleReader reader(ModuleReference ref) {
            return readers.computeIfAbsent(ref.descriptor().name(),n->{ try { return ref.open(); } catch(IOException e) { throw new java.io.UncheckedIOException(e); } });
        }
        private static String packageOf(String cn) { int dot=cn.lastIndexOf('.'); return dot<0?"":cn.substring(0,dot); }

        @Override protected Class<?> loadClass(String cn,boolean resolve) throws ClassNotFoundException {
            synchronized(getClassLoadingLock(cn)) {
                Class<?> c=findLoadedClass(cn);
                if(c==null) {
                    ModuleReference ref=packages.get(packageOf(cn));
                    if(ref!=null) { c=define(ref,cn); if(c==null) throw new ClassNotFoundException(cn); }
                    else if(cn.equals(FarModuleLayer.class.getName())) c=FarModuleLayer.class;
                    else c=getParent().loadClass(cn);
                }
                if(resolve) resolveClass(c);
                return c;
            }
        }
        @Override protected Class<?> findClass(String moduleName,String cn) {
            ModuleReference ref=packages.get(packageOf(cn));
            if(ref==null || (moduleName!=null && !ref.descriptor().name().equals(moduleName))) return null;
            synchronized(getClassLoadingLock(cn)) { Class<?> c=findLoadedClass(cn); return c!=null?c:define(ref,cn); }
        }
        private Class<?> define(ModuleReference ref,String cn) {
            try {
                Optional<ByteBuffer> buffer=reader(ref).read(cn.replace('.','/')+".class");
                if(buffer.isEmpty()) return null;
                ByteBuffer bb=buffer.get();
                byte[] bytes=new byte[bb.remaining()]; bb.get(bytes); reader(ref).release(bb);
                String self=FarModuleLayer.class.getName().replace('.','/');
                if(cn.equals(BOOTSTRAP)) bytes=ClassPatch.redirectStatic(bytes,"java/lang/ModuleLayer","boot",self);
                if(cn.equals(MODULE_CLASS_LOADER)) bytes=ClassPatch.redirectStatic(bytes,"java/lang/ClassLoader","getPlatformClassLoader",self);
                ProtectionDomain pd=domains.computeIfAbsent(ref,r->new ProtectionDomain(new CodeSource(r.location().map(u->{ try { return u.toURL(); } catch(Exception e) { return null; } }).orElse(null),(java.security.cert.Certificate[]) null),null,this,null));
                return defineClass(cn,bytes,0,bytes.length,pd);
            } catch(IOException e) { throw new java.io.UncheckedIOException(e); }
        }
        @Override protected URL findResource(String moduleName,String name) throws IOException {
            for(ModuleReference ref:new HashSet<>(packages.values())) {
                if(moduleName!=null && !ref.descriptor().name().equals(moduleName)) continue;
                Optional<java.net.URI> uri=reader(ref).find(name);
                if(uri.isPresent()) return uri.get().toURL();
            }
            return null;
        }
        @Override protected URL findResource(String name) { try { return findResource(null,name); } catch(IOException e) { return null; } }
        @Override protected Enumeration<URL> findResources(String name) throws IOException {
            List<URL> out=new ArrayList<>();
            for(ModuleReference ref:new HashSet<>(packages.values())) { Optional<java.net.URI> uri=reader(ref).find(name); if(uri.isPresent()) out.add(uri.get().toURL()); }
            return Collections.enumeration(out);
        }
    }
}
