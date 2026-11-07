package com.durkz.quantumhy.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

public final class QuantumHyAgent implements ClassFileTransformer {
    private static final String TARGET = "com/hypixel/hytale/server/core/universe/world/lighting/FloodLightCalculation";
    private static final String EXPECTED = "87cd68d50a14bb1fd4e9eee00fd9a271e6eface8fc52b94e577c07aabd2c9c65";
    private static final String ARGUMENTS = "Ljava/util/BitSet;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/ChunkLightDataBuilder;";

    public static void premain(String arguments, Instrumentation instrumentation) {
        System.setProperty("durkz.quantumhy.agent.requested", "true");
        instrumentation.addTransformer(new QuantumHyAgent());
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name) || redefining != null) return null;
        try {
            byte[] result = patch(loader, bytes);
            if (result == null) {
                System.err.println("QuantumHy agent: unrecognized lighting binary; native lighting retained.");
            } else {
                System.setProperty("durkz.quantumhy.agent.active", "true");
                System.out.println("QuantumHy agent: local and global lighting call sites patched.");
            }
            return result;
        } catch (Exception | LinkageError failure) {
            System.err.println("QuantumHy agent: transformation skipped: " + failure);
            return null;
        }
    }

    static byte[] patch(ClassLoader loader, byte[] bytes) throws Exception {
        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(EXPECTED)) return null;
        var classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(ClassHierarchyResolver.ofResourceParsing(loader)));
        var calls = new AtomicInteger();
        byte[] result = classFile.transformClass(classFile.parse(bytes), ClassTransform.transformingMethodBodies((code, element) -> {
            if (element instanceof InvokeInstruction invoke && invoke.method().owner().asInternalName().equals(TARGET)
                    && invoke.method().name().equalsString("propagateLight")
                    && invoke.typeSymbol().descriptorString().equals("(" + ARGUMENTS + ")V")) {
                calls.incrementAndGet();
                int light = code.allocateLocal(TypeKind.REFERENCE);
                int section = code.allocateLocal(TypeKind.REFERENCE);
                int queue = code.allocateLocal(TypeKind.REFERENCE);
                int calculation = code.allocateLocal(TypeKind.REFERENCE);
                code.astore(light).astore(section).astore(queue).astore(calculation);
                code.aload(calculation).aload(queue).aload(section).aload(light)
                        .invokestatic(ClassDesc.of("com.durkz.quantumhy.agent.AgentLightBridge"), "tryPropagate",
                                MethodTypeDesc.ofDescriptor("(L" + TARGET + ";" + ARGUMENTS + ")Z"));
                code.ifThen(Opcode.IFEQ, fallback -> fallback.aload(calculation).aload(queue).aload(section).aload(light).with(invoke));
            } else {
                code.with(element);
            }
        }));
        if (calls.get() != 2) throw new IllegalStateException("Expected two lighting call sites, found " + calls.get());
        return result;
    }
}
