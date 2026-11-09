package dev.mmlf.launcher.runtime;

import java.nio.file.Path;
import java.util.*;

/**
 * A Java launch of one game runtime, as its loader's own launch would run it: main class, class path, module path,
 * system properties, other JVM options and program arguments. The in-JVM backend starts its main class inside an
 * isolated class loader; {@link #command} renders it as the equivalent command line.
 *
 * @param jvmOptions JVM options other than class path, module path and {@code -D}, such as {@code --add-opens}
 */
public record JavaLaunch(String mainClass,List<Path> classpath,List<Path> modulePath,Map<String,String> properties,List<String> jvmOptions,List<String> args) {
    public JavaLaunch {
        Objects.requireNonNull(mainClass);
        classpath=List.copyOf(classpath); modulePath=List.copyOf(modulePath);
        properties=Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        jvmOptions=List.copyOf(jvmOptions); args=List.copyOf(args);
    }
    public JavaLaunch withProperties(Map<String,String> extra) {
        Map<String,String> merged=new LinkedHashMap<>(properties); merged.putAll(extra);
        return new JavaLaunch(mainClass,classpath,modulePath,merged,jvmOptions,args);
    }
    public JavaLaunch withArgs(List<String> replaced) { return new JavaLaunch(mainClass,classpath,modulePath,properties,jvmOptions,replaced); }
    public String property(String key) { return properties.get(key); }

    /** The value after {@code option} in the program arguments, or null. */
    public String arg(String option) {
        for(int i=0;i+1<args.size();i++) if(args.get(i).equals(option)) return args.get(i+1);
        return null;
    }
    /** Program arguments with {@code option}'s value replaced, or appended when absent. */
    public JavaLaunch withArg(String option,String value) {
        List<String> out=new ArrayList<>(args);
        int i=out.indexOf(option);
        if(i>=0 && i+1<out.size()) out.set(i+1,value); else { out.add(option); out.add(value); }
        return withArgs(out);
    }

    /** The equivalent {@code java ... main args} command line. */
    public List<String> command(Path java,List<String> extraJvm) {
        String separator=System.getProperty("path.separator");
        List<String> command=new ArrayList<>();
        command.add(java.toString());
        command.addAll(extraJvm);
        properties.forEach((k,v)->command.add("-D"+k+"="+v));
        command.addAll(jvmOptions);
        if(!modulePath.isEmpty()) { command.add("-p"); command.add(join(modulePath,separator)); }
        if(!classpath.isEmpty()) { command.add("-cp"); command.add(join(classpath,separator)); }
        command.add(mainClass);
        command.addAll(args);
        return command;
    }
    private static String join(List<Path> paths,String separator) {
        StringJoiner j=new StringJoiner(separator); for(Path p:paths) j.add(p.toString()); return j.toString();
    }

    /**
     * Splits a java command line (without the java executable) into its parts: the inverse of {@link #command}. Options
     * that take a separate value ({@code --add-opens x}, {@code --add-modules x}) keep their value next to them.
     */
    public static JavaLaunch parse(List<String> line) {
        List<Path> cp=new ArrayList<>(), mp=new ArrayList<>();
        Map<String,String> props=new LinkedHashMap<>();
        List<String> options=new ArrayList<>();
        String separator=System.getProperty("path.separator");
        int i=0;
        for(;i<line.size();i++) {
            String a=line.get(i);
            if(!a.startsWith("-")) break;
            switch(a) {
                case "-cp","-classpath","--class-path" -> split(line.get(++i),separator,cp);
                case "-p","--module-path" -> split(line.get(++i),separator,mp);
                case "--add-opens","--add-exports","--add-modules","--add-reads","--patch-module","--upgrade-module-path","--limit-modules" -> { options.add(a); options.add(line.get(++i)); }
                default -> {
                    if(a.startsWith("-D")) { int eq=a.indexOf('='); props.put(eq<0?a.substring(2):a.substring(2,eq),eq<0?"":a.substring(eq+1)); }
                    else options.add(a);
                }
            }
        }
        if(i>=line.size()) throw new IllegalArgumentException("java command without a main class");
        return new JavaLaunch(line.get(i),cp,mp,props,options,line.subList(i+1,line.size()));
    }
    private static void split(String value,String separator,List<Path> out) {
        for(String part:value.split(java.util.regex.Pattern.quote(separator))) if(!part.isBlank()) out.add(Path.of(part));
    }
}
