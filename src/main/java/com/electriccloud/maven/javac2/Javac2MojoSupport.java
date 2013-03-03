
//
// Copyright (c) 2012 Electric Cloud.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE
//

package com.electriccloud.maven.javac2;

import com.intellij.ant.AntClassWriter;
import com.intellij.ant.InstrumentationUtil;
import com.intellij.ant.PseudoClassLoader;
import com.intellij.compiler.notNullVerification.NonnullVerifyingInstrumenter;
import com.intellij.compiler.notNullVerification.NotNullVerifyingInstrumenter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jfrog.maven.annomojo.annotations.MojoParameter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.EmptyVisitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;

import static java.io.File.pathSeparator;

//
// Copyright (c) 2012 Electric Cloud.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE
//
public abstract class Javac2MojoSupport
    extends AbstractMojo
{

    //~ Instance fields --------------------------------------------------------

    @MojoParameter(
        expression = "${project}",
        readonly   = true,
        required   = true
    )
    protected MavenProject m_project;

    //~ Methods ----------------------------------------------------------------

    protected void instrumentNotNull(
            @NotNull File             outputDirectory,
            @NotNull Iterable<String> classPathElements)
        throws MojoExecutionException
    {

        if (!outputDirectory.isDirectory()) {

            // Nothing to do
            return;
        }

        // Make sure instrumented output directory exists
        File             instrumentedDirectory = new File(
                outputDirectory.getParentFile(),
                outputDirectory.getName() + "-instrumented");
        InstrumentResult result                = instrumentNotNull(
                outputDirectory, instrumentedDirectory,
                buildClasspathClassLoader(classPathElements));

        getLog().info("Up-to-date: " + result.getSkipped()
                + " Updated: " + result.getUpdated()
                + " Created: " + result.getInstrumented());
    }

    /**
     * Create class loader based on classpath, bootclasspath, and sourcepath.
     *
     * @param   classpathElements
     *
     * @return  a URL classLoader
     *
     * @throws  MojoExecutionException
     */
    private PseudoClassLoader buildClasspathClassLoader(
            @NotNull Iterable<String> classpathElements)
        throws MojoExecutionException
    {
        StringBuilder classPath = new StringBuilder();

        for (String pathElement : classpathElements) {
            classPath.append(pathSeparator);
            classPath.append(pathElement);
        }

        if (getLog().isDebugEnabled()) {
            getLog().debug("classpath=" + classPath);
        }

        try {
            return InstrumentationUtil.createPseudoClassLoader(
                classPath.toString());
        }
        catch (MalformedURLException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }

    /**
     * @param   classDir       The output directory (e.g. target/classes,
     *                         target/test-classes
     * @param   instrumentDir  The shadow directory used to keep timestamps of
     *                         existing classes (e.g.
     *                         target/classes-instrumented,
     *                         target/test-classes-instrumented)
     * @param   loader
     *
     * @return
     *
     * @throws  MojoExecutionException
     */
    @NotNull
    @SuppressWarnings(
        {"ConstantConditions", "OverlyLongMethod", "OverlyComplexMethod"}
    )
    private InstrumentResult instrumentNotNull(
            @NotNull File              classDir,
            @NotNull File              instrumentDir,
            @NotNull PseudoClassLoader loader)
        throws MojoExecutionException
    {

        // Create the shadow directory if it doesn't exist
        if (!instrumentDir.isDirectory() && !instrumentDir.mkdirs()) {
            throw new MojoExecutionException(instrumentDir.getPath()
                    + " is not a directory, or cannot be created");
        }

        File[]           files  = classDir.listFiles();
        InstrumentResult result = new InstrumentResult();

        for (File file : files) {

            if (file.isDirectory()) {
                result.add(instrumentNotNull(file,
                        new File(instrumentDir, file.getName()), loader));

                continue;
            }

            @NonNls String name = file.getName();

            if (!name.endsWith(".class")) {
                continue;
            }

            // Check the timestamp in the shadow directory and skip if the
            // class file is not newer
            File shadowFile = new File(instrumentDir, file.getName());

            // If the shadow file doesn't exist, this is a new class file.
            // Create the shadow file
            if (shadowFile.exists()) {

                // Skip up-to-date files
                if (shadowFile.lastModified() >= file.lastModified()) {

                    if (getLog().isDebugEnabled()) {
                        getLog().debug("Up-to-date @NotNull/@Nonnull assertions: "
                                + file.getPath());
                    }

                    result.skipped();

                    continue;
                }

                if (getLog().isDebugEnabled()) {
                    getLog().debug("Updating @NotNull assertions: "
                            + file.getPath());
                }

                result.updated();
            }
            else {

                try {

                    // noinspection ResultOfMethodCallIgnored
                    shadowFile.createNewFile();
                }
                catch (IOException e) {
                    throw new MojoExecutionException("Unable to create "
                            + shadowFile.getPath() + ": " + e.getMessage(), e);
                }

                if (getLog().isDebugEnabled()) {
                    getLog().debug("Creating @NotNull assertions: "
                            + file.getPath());
                }

                result.instrumented();
            }

            try {
                instrumentClassFileNotNull(loader, file);
                instrumentClassFileNonnull(loader, file);

                // Set the timestamp of the shadow file
                if (!shadowFile.setLastModified(file.lastModified())) {
                    throw new MojoExecutionException(
                        "Unable to set lastModified on "
                            + shadowFile.getAbsolutePath());
                }
            }
            catch (IOException e) {
                getLog().warn("Failed to instrument @NotNull/@Nonnull assertion for "
                        + file.getPath() + ": " + e.getMessage(), e);
            }
            catch (MojoExecutionException e) {
                getLog().warn("Failed to instrument @NotNull/@Nonnull assertion for "
                        + file.getPath() + ": " + e.getMessage(), e);
            }
            catch (Exception e) {
                throw new MojoExecutionException(
                    "@NotNull/@Nonnull instrumentation failed for " + file.getPath()
                        + ": " + e.toString());
            }
        }

        return result;
    }

    //~ Methods ----------------------------------------------------------------

    private static void instrumentClassFileNotNull(
            @NotNull PseudoClassLoader loader,
            @NotNull File file)
        throws IOException
    {
        FileInputStream inputStream = new FileInputStream(file);

        try {
            ClassReader reader = new ClassReader(inputStream);
            ClassWriter writer = new AntClassWriter(getAsmClassWriterFlags(
                        getClassFileVersion(reader)), loader);

            //
            NotNullVerifyingInstrumenter instrumenter =
                new NotNullVerifyingInstrumenter(writer);

            reader.accept(instrumenter, 0);

            if (!instrumenter.isModification()) {
                return;
            }

            FileOutputStream fileOutputStream = new FileOutputStream(file);

            try {
                fileOutputStream.write(writer.toByteArray());
            }
            finally {
                fileOutputStream.close();
            }
        }
        finally {
            inputStream.close();
        }
    }

    /**
     * Copy of {@link Javac2MojoSupport#instrumentClassFileNotNull(com.intellij.ant.PseudoClassLoader, java.io.File)} method
     * which instruments JSR 205 Nonnull annotation
     */
    private static void instrumentClassFileNonnull(
            @NotNull PseudoClassLoader loader,
            @NotNull File file)
            throws IOException
    {
        FileInputStream inputStream = new FileInputStream(file);

        try {
            ClassReader reader = new ClassReader(inputStream);
            ClassWriter writer = new AntClassWriter(getAsmClassWriterFlags(
                    getClassFileVersion(reader)), loader);

            //
            NonnullVerifyingInstrumenter instrumenter =
                    new NonnullVerifyingInstrumenter(writer);

            reader.accept(instrumenter, 0);

            if (!instrumenter.isModification()) {
                return;
            }

            FileOutputStream fileOutputStream = new FileOutputStream(file);

            try {
                fileOutputStream.write(writer.toByteArray());
            }
            finally {
                fileOutputStream.close();
            }
        }
        finally {
            inputStream.close();
        }
    }


    /**
     * @param   version
     *
     * @return  the flags for class writer
     */
    private static int getAsmClassWriterFlags(int version)
    {
        return version >= Opcodes.V1_6 && version != Opcodes.V1_1
        ? ClassWriter.COMPUTE_FRAMES
        : ClassWriter.COMPUTE_MAXS;
    }

    private static int getClassFileVersion(@NotNull ClassReader reader)
    {
        final int[] classfileVersion = new int[1];

        reader.accept(new EmptyVisitor() {
                @Override
                @SuppressWarnings("RefusedBequest")
                public void visit(
                        int      version,
                        int      access,
                        String   name,
                        String   signature,
                        String   superName,
                        String[] interfaces)
                {
                    classfileVersion[0] = version;
                }
            }, 0);

        return classfileVersion[0];
    }

    //~ Inner Classes ----------------------------------------------------------

    private static class InstrumentResult
    {

        //~ Instance fields ----------------------------------------------------

        private int m_instrumented;
        private int m_skipped;
        private int m_updated;

        //~ Methods ------------------------------------------------------------

        public void add(@NotNull InstrumentResult result)
        {
            m_instrumented += result.m_instrumented;
            m_skipped      += result.m_skipped;
            m_updated      += result.m_updated;
        }

        public void instrumented()
        {
            m_instrumented++;
        }

        public void skipped()
        {
            m_skipped++;
        }

        public void updated()
        {
            m_updated++;
        }

        public int getInstrumented()
        {
            return m_instrumented;
        }

        public int getSkipped()
        {
            return m_skipped;
        }

        public int getUpdated()
        {
            return m_updated;
        }
    }
}
