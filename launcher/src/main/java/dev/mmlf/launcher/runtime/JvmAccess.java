package dev.mmlf.launcher.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

/**
 * What a JVM started by someone else lacks for a second runtime that its own launch would have set up with JVM options:
 * {@code --add-opens}/{@code --add-exports} for modules defined at run time, and file-system providers from a module
 * layer the JDK's provider lookup does not search. Uses the JDK's trusted lookup, reached through
 * {@code sun.misc.Unsafe}; nothing here changes what either loader does, only what its JVM options would have granted.
 */
final class JvmAccess {
    private static MethodHandles.Lookup trusted;
    private JvmAccess() {}

    static synchronized MethodHandles.Lookup trusted() {
        if(trusted!=null) return trusted;
        try {
            Field unsafeField=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe=unsafeField.get(null);
            Field impl=MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Class<?> u=unsafe.getClass();
            Object base=u.getMethod("staticFieldBase",Field.class).invoke(unsafe,impl);
            long offset=(long) u.getMethod("staticFieldOffset",Field.class).invoke(unsafe,impl);
            trusted=(MethodHandles.Lookup) u.getMethod("getObject",Object.class,long.class).invoke(unsafe,base,offset);
            return trusted;
        } catch(ReflectiveOperationException|RuntimeException e) {
            throw new IllegalStateException("this JVM does not allow the module access a second runtime needs; add its JVM options to the launch instead",e);
        }
    }

    /** {@code --add-opens source/pkg=target} or {@code --add-exports} for modules that already exist. */
    static void open(Module source,String pkg,Module target,boolean opens) {
        try {
            MethodHandle h=trusted().findVirtual(Module.class,opens?"implAddOpens":"implAddExports",MethodType.methodType(void.class,String.class,Module.class));
            h.invoke(source,pkg,target);
        } catch(Throwable t) { throw new IllegalStateException((opens?"--add-opens ":"--add-exports ")+source.getName()+"/"+pkg+"="+target.getName()+" failed",t); }
    }

    /**
     * Applies {@code --add-opens a/p=b} and {@code --add-exports a/p=b} from a launch's JVM options; modules are looked
     * up in {@code layer} and its parents. Returns the options it could not apply.
     */
    static List<String> applyModuleOptions(List<String> options,ModuleLayer layer) {
        List<String> skipped=new ArrayList<>();
        for(int i=0;i<options.size();i++) {
            String o=options.get(i);
            if(!o.equals("--add-opens") && !o.equals("--add-exports")) continue;
            String value=options.get(++i);
            int slash=value.indexOf('/'), eq=value.indexOf('=');
            Optional<Module> source=layer.findModule(value.substring(0,slash));
            String pkg=value.substring(slash+1,eq);
            for(String targetName:value.substring(eq+1).split(",")) {
                Optional<Module> target=layer.findModule(targetName);
                if(source.isEmpty() || target.isEmpty()) { skipped.add(o+" "+value); continue; }
                open(source.get(),pkg,target.get(),o.equals("--add-opens"));
            }
        }
        return skipped;
    }

    /** Adds providers of URI schemes the JDK does not know yet (union, jij) to its installed-provider list. */
    static List<String> installFileSystems(ModuleLayer layer) {
        List<FileSystemProvider> current=FileSystemProvider.installedProviders();
        Set<String> schemes=new HashSet<>();
        for(FileSystemProvider p:current) schemes.add(p.getScheme());
        List<FileSystemProvider> merged=new ArrayList<>(current);
        List<String> added=new ArrayList<>();
        for(FileSystemProvider p:ServiceLoader.load(layer,FileSystemProvider.class)) {
            if(schemes.add(p.getScheme())) { merged.add(p); added.add(p.getScheme()); }
        }
        if(added.isEmpty()) return added;
        try {
            MethodHandle setter=trusted().findStaticSetter(FileSystemProvider.class,"installedProviders",List.class);
            setter.invoke(Collections.unmodifiableList(merged));
        } catch(Throwable t) { throw new IllegalStateException("cannot install file systems "+added,t); }
        return added;
    }
}
