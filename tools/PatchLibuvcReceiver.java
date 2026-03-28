import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

public class PatchLibuvcReceiver {

  private static final String TARGET_CLASS = "com/jiangdg/usb/USBMonitor.class";
  private static final String CONTEXT_CLASS = "android/content/Context";
  private static final String CONTEXT_COMPAT_CLASS = "androidx/core/content/ContextCompat";
  private static final String REGISTER_RECEIVER_DESC =
      "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;";
  private static final String CONTEXT_COMPAT_DESC =
      "(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;";

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: PatchLibuvcReceiver <path-to-aar>");
    }

    Path aarPath = Path.of(args[0]);
    if (!Files.exists(aarPath)) {
      throw new IllegalArgumentException("AAR not found: " + aarPath);
    }

    Path backupPath = aarPath.resolveSibling(aarPath.getFileName() + ".bak");
    if (!Files.exists(backupPath)) {
      Files.copy(aarPath, backupPath, StandardCopyOption.COPY_ATTRIBUTES);
    }

    byte[] patchedAar = patchAar(aarPath);
    Files.write(aarPath, patchedAar);
    System.out.println("Patched " + aarPath);
  }

  private static byte[] patchAar(Path aarPath) throws IOException {
    try (
        ZipFile zipFile = new ZipFile(aarPath.toFile());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {

      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      boolean patched = false;
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        ZipEntry newEntry = new ZipEntry(entry.getName());
        zipOutputStream.putNextEntry(newEntry);
        if ("classes.jar".equals(entry.getName())) {
          byte[] patchedJar = patchClassesJar(new ByteArrayInputStream(readAllBytes(zipFile, entry)));
          zipOutputStream.write(patchedJar);
          patched = true;
        } else {
          zipOutputStream.write(readAllBytes(zipFile, entry));
        }
        zipOutputStream.closeEntry();
      }

      if (!patched) {
        throw new IllegalStateException("classes.jar not found in AAR");
      }
      zipOutputStream.finish();
      return outputStream.toByteArray();
    }
  }

  private static byte[] readAllBytes(ZipFile zipFile, ZipEntry entry) throws IOException {
    try (InputStream inputStream = zipFile.getInputStream(entry)) {
      return inputStream.readAllBytes();
    }
  }

  private static byte[] patchClassesJar(InputStream classesJarStream) throws IOException {
    try (
        JarInputStream jarInputStream = new JarInputStream(classesJarStream);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        JarOutputStream jarOutputStream = new JarOutputStream(outputStream)) {

      JarEntry jarEntry;
      boolean patched = false;
      while ((jarEntry = jarInputStream.getNextJarEntry()) != null) {
        JarEntry newEntry = new JarEntry(jarEntry.getName());
        jarOutputStream.putNextEntry(newEntry);
        byte[] entryBytes = jarInputStream.readAllBytes();
        if (TARGET_CLASS.equals(jarEntry.getName())) {
          jarOutputStream.write(patchUsbMonitor(entryBytes));
          patched = true;
        } else {
          jarOutputStream.write(entryBytes);
        }
        jarOutputStream.closeEntry();
        jarInputStream.closeEntry();
      }

      if (!patched) {
        throw new IllegalStateException("USBMonitor.class not found in classes.jar");
      }
      jarOutputStream.finish();
      return outputStream.toByteArray();
    }
  }

  private static byte[] patchUsbMonitor(byte[] classBytes) {
    ClassReader classReader = new ClassReader(classBytes);
    ClassWriter classWriter = new ClassWriter(classReader, 0);
    ClassVisitor classVisitor =
        new ClassVisitor(Opcodes.ASM8, classWriter) {
          @Override
          public MethodVisitor visitMethod(
              int access,
              String name,
              String descriptor,
              String signature,
              String[] exceptions) {
            MethodVisitor methodVisitor =
                super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!"register".equals(name) || !"()V".equals(descriptor)) {
              return methodVisitor;
            }
            return new MethodVisitor(Opcodes.ASM8, methodVisitor) {
              @Override
              public void visitMethodInsn(
                  int opcode,
                  String owner,
                  String methodName,
                  String methodDescriptor,
                  boolean isInterface) {
                if (opcode == Opcodes.INVOKEVIRTUAL
                    && CONTEXT_CLASS.equals(owner)
                    && "registerReceiver".equals(methodName)
                    && REGISTER_RECEIVER_DESC.equals(methodDescriptor)) {
                  super.visitFieldInsn(
                      Opcodes.GETSTATIC,
                      CONTEXT_COMPAT_CLASS,
                      "RECEIVER_NOT_EXPORTED",
                      "I");
                  super.visitMethodInsn(
                      Opcodes.INVOKESTATIC,
                      CONTEXT_COMPAT_CLASS,
                      "registerReceiver",
                      CONTEXT_COMPAT_DESC,
                      false);
                  return;
                }
                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
              }
            };
          }
        };
    classReader.accept(classVisitor, 0);
    return classWriter.toByteArray();
  }
}
