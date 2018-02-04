package org.tastefuljava.javadep;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class Main {
    static {
        // This is especially useful on Mac OS to avoid the default app to be
        // Launched and appear in the dock and in the menu bar
        System.setProperty("java.awt.headless", "true");
        
        // Default logging settings
        if (System.getProperty("java.util.logging.config.file") == null) {
            // Use default logging configuration
            try (InputStream inputStream = Main.class.getResourceAsStream(
                    "default-logging.properties")) {
                LogManager.getLogManager().readConfiguration(inputStream);
            } catch (final IOException e) {
                Logger.getLogger(Main.class.getName()).severe(e.getMessage());
            }
        }
    }

    static enum Flag {
        VERBOSE, QUIET, DEBUG,
        SYSTEM, CODE, DECLARATION;

        public String getName() {
            return name().toLowerCase().replace('_', '-');
        }

        @Override
        public String toString() {
            return "--" + getName();
        }
    }

    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final Pattern JAR_URL_PATTERN
            = Pattern.compile("^jar:(.*)!/(.*)$");

    private final ClassLoader cl;
    private final List<URL> classPath = new ArrayList<>();
    private final List<String> classes = new ArrayList<>();
    private final Set<String> classSet = new HashSet<>();
    private final Set<String> jars = new HashSet<>();
    private final Set<Flag> flags = EnumSet.of(Flag.CODE, Flag.DECLARATION);
    private int current = 0;

    public static void main(String[] args) {
        try {
            new Main(args).process();
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
    }

    public Main(String[] args) throws IOException {
        int st = 0;
        for (String arg: args) {
            switch (st) {
                case 0:
                    switch (arg) {
                        case "-c":
                        case "--class":
                            st = 1;
                            break;
                        case "-r":
                        case "--root":
                            st = 2;
                            break;
                        case "-p":
                        case "--path-element":
                            st = 3;
                            break;
                        case "-v":
                        case "--verbose":
                            flags.add(Flag.VERBOSE);
                            break;
                        case "-q":
                        case "--quiet":
                            flags.add(Flag.QUIET);
                            break;
                        case "-d":
                        case "--debug":
                            flags.add(Flag.DEBUG);
                            break;
                        case "-s":
                        case "--system":
                            flags.add(Flag.SYSTEM);
                            break;
                        case "-S":
                        case "--no-system":
                            flags.remove(Flag.SYSTEM);
                            break;
                        case "-o":
                        case "--code":
                            flags.add(Flag.CODE);
                            break;
                        case "-O":
                        case "--no-code":
                            flags.remove(Flag.CODE);
                            break;
                        case "-e":
                        case "--decl":
                        case "--declaration":
                            flags.add(Flag.CODE);
                            break;
                        case "-E":
                        case "--no-decl":
                        case "--no-declaration":
                            flags.remove(Flag.DECLARATION);
                            break;
                    }
                    break;
                case 1:
                    addClass(arg);
                    st = 0;
                    break;
                case 2:
                    File dir = new File(arg);
                    classPath.addAll(listJars(dir));
                    st = 0;
                    break;
                case 3:
                    classPath.add(new File(arg).toURI().toURL());
                    st = 0;
                    break;
            }
        }
        LOG.log(Level.INFO, "found {0} jars", classPath.size());
        cl = URLClassLoader.newInstance(
                classPath.toArray(new URL[classPath.size()]), null);
    }

    private void setLoggingLevel() {
        Logger log = LogManager.getLogManager().getLogger("");
        if (log != null) {
            Level level = Level.WARNING;
            if (flags.contains(Flag.QUIET)) {
                level = Level.SEVERE;
            }
            if (flags.contains(Flag.VERBOSE)) {
                level = Level.INFO;
            }
            if (flags.contains(Flag.DEBUG)) {
                level = Level.FINEST;
            }
            log.setLevel(level);
        }
    }

    private void process() throws IOException {
        setLoggingLevel();
        while (current < classes.size()) {
            String cname = classes.get(current++);
            analyseClass(cname);
        }
        if (!classes.isEmpty()) {
            listClasses();
        }
        if (!jars.isEmpty()) {
            listJars();
        }
    }

    private void listClasses() {
        String[] classArray = classes.toArray(new String[classes.size()]);
        Arrays.sort(classArray);
        System.out.println("Classes:");
        for (String cls: classArray) {
            System.out.println("    " + cls);
        }
    }

    private void listJars() {
        String[] jarArray = jars.toArray(new String[jars.size()]);
        Arrays.sort(jarArray);
        System.out.println("Required JARs:");
        for (String jar: jarArray) {
            System.out.println("    " + jar);
        }
    }

    private static List<URL> listJars(File dir) {
        List<URL> result = new ArrayList<>();
        listJars(dir, result);
        return result;
    }

    private static void listJars(File dir, List<URL> result) {
        dir.listFiles((f) -> {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                try {
                    result.add(f.toURI().toURL());
                } catch (IOException ex) {
                    LOG.log(Level.SEVERE, null, ex);
                }
            } else if (f.isDirectory()) {
                listJars(f, result);
            }
            return false;
        });
    }

    private void addClass(String className) {
        if (!classSet.contains(className) && !className.startsWith("[")) {
            LOG.log(Level.FINE, "Dependency found: {0}", className);
            classSet.add(className);
            classes.add(className);
        }
    }

    private void analyseClass(String cname) throws IOException {
        String resName = cname.replace('.', '/') + ".class";
        URL url = cl.getResource(resName);
        if (url == null) {
            LOG.log(Level.WARNING, "Class not found: {0}", cname);
            return;
        }
        String urlStr = url.toString();
        Matcher matcher = JAR_URL_PATTERN.matcher(urlStr);
        if (!matcher.matches()) {
            LOG.log(Level.FINE, "Not a jar URL: {0}", urlStr);
            return;
        }
        String jarUrl = matcher.group(1);
        if (!flags.contains(Flag.SYSTEM)) {
            if (!classPath.contains(new URL(jarUrl))) {
                LOG.log(Level.FINE, "Jar not in classpath: {0}", jarUrl);
                return;
            }
        }
        jars.add(jarUrl);
        try (InputStream in = url.openStream()) {
            LOG.log(Level.INFO, "Reading class {0} from {1}",
                    new Object[] {cname, url});
            ClassReader creader = new ClassReader(in);
            creader.accept(new ClassVisitor(Opcodes.ASM6) {
                @Override
                public FieldVisitor visitField(int access, String name,
                        String desc, String signature, Object value) {
                    if (flags.contains(Flag.DECLARATION)) {
                        refType(desc, 0);
                    }
                    return super.visitField(access, name, desc, signature,
                            value);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name,
                        String desc, String signature, String[] exceptions) {
                    if (flags.contains(Flag.DECLARATION)) {
                        methodDesc(desc);
                    }
                    if (flags.contains(Flag.CODE)) {
                        return new MethodVisitor(Opcodes.ASM6) {
                            @Override
                            public void visitMethodInsn(int opcode,
                                    String owner, String name, String desc,
                                    boolean itf) {
                                addClass(owner.replace('/', '.'));
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String owner,
                                    String name, String desc) {
                                addClass(owner.replace('/', '.'));
                            }                            
                        };
                    } else {
                        return super.visitMethod(access, name, desc, signature,
                                exceptions);
                    }
                }
            }, ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
    }

    private int refType(String desc, int pos) {
        char c = desc.charAt(pos++);
        while (c == '[') {
            c = desc.charAt(pos++);
        }
        if (c == 'L') {
            int start = pos;
            do {
                c = desc.charAt(pos++);
            } while (c != ';');
            addClass(desc.substring(start, pos-1));
        }
        return pos;
    }

    private void methodDesc(String desc) {
        int pos = 0;
        if (desc.charAt(pos++) != '(') {
            LOG.log(Level.SEVERE, "Invalid method descriptor: {0}", desc);
        }
        while (desc.charAt(pos) != ')') {
            pos = refType(desc, pos);
        }
        refType(desc, ++pos);
    }
}
