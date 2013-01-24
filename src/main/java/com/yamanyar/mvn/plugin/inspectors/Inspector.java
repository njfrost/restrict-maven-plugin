package com.yamanyar.mvn.plugin.inspectors;

import com.yamanyar.mvn.plugin.RestrictedAccessException;
import com.yamanyar.mvn.plugin.utils.Extractor;
import com.yamanyar.mvn.plugin.utils.WildcardMatcher;
import javassist.ClassPool;
import javassist.CtClass;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Inspects the class files, jar files and war files inside given ear resource
 *
 * @author Kaan Yamanyar
 */

public class Inspector {

    final private static String errorMessage = "Restricted access from: %s to: %s due to rule [%d-%d]";
    private final Log log;
    private final Map<WildcardMatcher, Set<WildcardMatcher>> restrictionsMap;
    private int count = 0;

    public Inspector(Log log, Map<WildcardMatcher, Set<WildcardMatcher>> restrictionsMap) {
        this.log = log;
        this.restrictionsMap = restrictionsMap;
    }

    public void inspectJar(String path) throws IOException {
        JarFile jarFile = new JarFile(path);
        Enumeration<JarEntry> jarEntries = jarFile.entries();
        while (jarEntries.hasMoreElements()) {
            JarEntry jarEntry = jarEntries.nextElement();
            String entryName = jarEntry.getName();
            InputStream entryStream = null;
            if (entryName.endsWith(".class")) {
                try {
                    entryStream = jarFile.getInputStream(jarEntry);
                    inspectClass(entryStream);

                } finally {
                    if (entryStream != null) entryStream.close();
                }
            }
        }
    }

    public void inspectWar(String path) throws IOException {
        inspectJar(path);
        File war = new File(path);
        JarFile warFile = new JarFile(path);

        File extractedDir = war.isFile() ? Extractor.extract(warFile) : war;
        Enumeration<JarEntry> jarEntries = warFile.entries();

        while (jarEntries.hasMoreElements()) {

            JarEntry jarEntry = jarEntries.nextElement();
            String entryName = jarEntry.getName();
            if (entryName.endsWith(".jar")) {
                File subJarFile = new File(extractedDir.getCanonicalPath(), entryName);
                inspectJar(subJarFile.getAbsolutePath());
            }

        }
    }

    public void inspectEar(String path) throws IOException {
        inspectJar(path);
        File war = new File(path);
        JarFile warFile = new JarFile(path);

        File extractedDir = war.isFile() ? Extractor.extract(warFile) : war;
        Enumeration<JarEntry> jarEntries = warFile.entries();

        while (jarEntries.hasMoreElements()) {

            JarEntry jarEntry = jarEntries.nextElement();
            String entryName = jarEntry.getName();
            if (entryName.endsWith(".jar")) {
                File subJarFile = new File(extractedDir.getCanonicalPath(), entryName);
                inspectJar(subJarFile.getAbsolutePath());
            } else if (entryName.endsWith(".war")) {
                File warJarFile = new File(extractedDir.getCanonicalPath(), entryName);
                inspectWar(warJarFile.getAbsolutePath());
            }

        }
    }

    protected void inspectClass(InputStream entryStream) throws IOException {
        ClassPool classPool = new ClassPool();
        CtClass ctClz = classPool.makeClass(entryStream);

        Set<WildcardMatcher> fromList = restrictionsMap.keySet();


        //TODO Optimize this code block

        for (WildcardMatcher from : fromList) {
            if (from.match(ctClz.getName())) {
                Collection refClasses = ctClz.getRefClasses();
                for (Object targetReference : refClasses) {
                    Set<WildcardMatcher> restrictedTargets = restrictionsMap.get(from);
                    for (WildcardMatcher restrictedTarget : restrictedTargets) {
                        if (restrictedTarget.match((String) targetReference)) {
                            count++;
                            log.error(String.format(errorMessage, ctClz.getName(), targetReference, from.getRuleNo(), restrictedTarget.getRuleNo()));
                        }
                    }
                }
            }
        }


    }

    /**
     * Returns number of exceptions!
     *
     * @param artifacts
     * @return
     * @throws IOException
     */
    public void inspectArtifacts(Set<Artifact> artifacts) throws IOException {
        for (Artifact artifact : artifacts) {
            log.debug("Inspecting " + artifact.toString());
            String path = artifact.getFile().getPath();
            if (path.endsWith(".jar")) {
                inspectJar(path);
            } else if (path.endsWith(".war")) {
                inspectWar(path);
            } else if (path.endsWith(".ear")) {
                inspectEar(path);
            } else if (path.endsWith(".class")) {
                File classFile = artifact.getFile();
                inspectClassFile(classFile);
            }


        }

    }

    private void inspectClassFile(File classFile) throws IOException {
        InputStream is = null;
        try {
            is = new FileInputStream(classFile);
            inspectClass(is);
        } finally {
            if (is != null) is.close();
        }
    }

    public void inspectFolder(File buildDirectory) throws IOException {
        Collection<File> files = FileUtils.listFiles(buildDirectory, new String[]{"class"}, true);
        for (File file : files)
            inspectClassFile(file);

    }

    public void breakIfError(boolean continueOnError) throws RestrictedAccessException {
        if (count > 0) {
            log.error("Build is broken due to " + count + " restriction policies!");
            if (continueOnError){
                log.error("Build is not broken since continueOnError is set to true!");
            }else{
                throw new RestrictedAccessException(count);
            }
        } else log.info("No restricted access is found");
    }
}