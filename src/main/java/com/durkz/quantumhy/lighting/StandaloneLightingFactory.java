package com.durkz.quantumhy.lighting;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;
import com.hypixel.hytale.server.core.universe.world.lighting.ChunkLightingManager;
import com.hypixel.hytale.server.core.universe.world.lighting.FloodLightCalculation;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.BitSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class StandaloneLightingFactory {
    private static final ClassDesc NATIVE = ClassDesc.of(FloodLightCalculation.class.getName());
    private static final ClassDesc GENERATED = ClassDesc.of(NATIVE.packageName() + ".QuantumHyDenseLighting");
    private static final ClassDesc HANDLE = ClassDesc.of(MethodHandle.class.getName());
    private static final String PROPAGATE_ARGUMENTS = "Ljava/util/BitSet;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/ChunkLightDataBuilder;";
    private static final MethodTypeDesc DISPATCH = MethodTypeDesc.ofDescriptor("(" + NATIVE.descriptorString() + PROPAGATE_ARGUMENTS + ")Z");
    private static final Set<String> METHODS = Set.of("calculateLight", "updateLocalLight", "updateGlobalLight", "floodChunkSection");
    private static final MethodType DISPATCH_TYPE = MethodType.methodType(boolean.class, FloodLightCalculation.class,
            BitSet.class, BlockSection.class, ChunkLightDataBuilder.class);
    private static MethodHandle constructor;
    private static VarHandle dispatch;

    private StandaloneLightingFactory() { }

    static synchronized FloodLightCalculation create(ChunkLightingManager manager, HytaleLogger logger) throws Throwable {
        if (!NativeLightKernel.supported()) return null;
        if (constructor == null) initialize();
        var result = (FloodLightCalculation) constructor.invoke(manager);
        var bridge = new DenseLightPropagation(logger);
        var target = MethodHandles.lookup().findVirtual(DenseLightPropagation.class, "propagate", DISPATCH_TYPE).bindTo(bridge);
        dispatch.set(result, target);
        return result;
    }

    private static void initialize() throws Throwable {
        var lookup = engineLookup();
        byte[] original;
        try (var input = FloodLightCalculation.class.getResourceAsStream("FloodLightCalculation.class")) {
            if (input == null) throw new IllegalStateException("Native lighting bytecode unavailable");
            original = input.readAllBytes();
        }
        var resolver = ((ClassHierarchyResolver) type -> type.equals(GENERATED)
                ? ClassHierarchyResolver.ClassHierarchyInfo.ofClass(NATIVE) : null)
                .orElse(ClassHierarchyResolver.ofResourceParsing(FloodLightCalculation.class.getClassLoader()));
        var classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
        var model = classFile.parse(original);
        var propagationCalls = new AtomicInteger();
        var calculationCalls = new AtomicInteger();
        byte[] generated = classFile.build(GENERATED, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL).withSuperclass(NATIVE);
            builder.withField("qhDispatch", HANDLE, ClassFile.ACC_PRIVATE | ClassFile.ACC_VOLATILE);
            var manager = ClassDesc.of(ChunkLightingManager.class.getName());
            builder.withMethodBody("<init>", MethodTypeDesc.of(ClassDesc.ofDescriptor("V"), manager),
                    ClassFile.ACC_PUBLIC, code -> code.aload(0).aload(1)
                            .invokespecial(NATIVE, "<init>", MethodTypeDesc.of(ClassDesc.ofDescriptor("V"), manager)).return_());
            for (var method : model.methods()) {
                if (!METHODS.contains(method.methodName().stringValue())) continue;
                builder.transformMethod(method, MethodTransform.transformingCode((code, element) -> {
                    if (element instanceof InvokeInstruction invoke && invoke.owner().asSymbol().equals(NATIVE)) {
                        String name = invoke.name().stringValue();
                        if (name.equals("updateLocalLight") || name.equals("updateGlobalLight") || name.equals("floodChunkSection")) {
                            calculationCalls.incrementAndGet();
                            code.invoke(invoke.opcode(), GENERATED, name, invoke.typeSymbol(), false);
                            return;
                        }
                        if (name.equals("propagateLight") && invoke.typeSymbol().descriptorString().equals("(" + PROPAGATE_ARGUMENTS + ")V")) {
                            propagationCalls.incrementAndGet();
                            int light = code.allocateLocal(TypeKind.REFERENCE);
                            int section = code.allocateLocal(TypeKind.REFERENCE);
                            int queue = code.allocateLocal(TypeKind.REFERENCE);
                            int calculation = code.allocateLocal(TypeKind.REFERENCE);
                            code.astore(light).astore(section).astore(queue).astore(calculation);
                            code.aload(calculation).getfield(GENERATED, "qhDispatch", HANDLE)
                                    .aload(calculation).aload(queue).aload(section).aload(light)
                                    .invokevirtual(HANDLE, "invokeExact", DISPATCH);
                            code.ifThen(Opcode.IFEQ, fallback -> fallback.aload(calculation).aload(queue).aload(section).aload(light).with(invoke));
                            return;
                        }
                    }
                    code.with(element);
                }));
            }
        });
        if (propagationCalls.get() != 2 || calculationCalls.get() != 3)
            throw new IllegalStateException("Unexpected native lighting call structure: propagation=" + propagationCalls.get()
                    + ", calculation=" + calculationCalls.get());
        if (!classFile.verify(generated).isEmpty()) throw new IllegalStateException("Generated lighting verification failed");
        var hidden = lookup.defineHiddenClass(generated, true, MethodHandles.Lookup.ClassOption.NESTMATE);
        var type = hidden.lookupClass();
        var newConstructor = hidden.findConstructor(type, MethodType.methodType(void.class, ChunkLightingManager.class))
                .asType(MethodType.methodType(FloodLightCalculation.class, ChunkLightingManager.class));
        var newDispatch = hidden.findVarHandle(type, "qhDispatch", MethodHandle.class);
        dispatch = newDispatch;
        constructor = newConstructor;
    }

    private static MethodHandles.Lookup engineLookup() throws Throwable {
        var factory = ClassDesc.of(NATIVE.packageName() + ".QuantumHyLightingLookup");
        var lookupType = ClassDesc.of(MethodHandles.Lookup.class.getName());
        var handles = ClassDesc.of(MethodHandles.class.getName());
        byte[] bytes = ClassFile.of().build(factory, builder -> builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                .withMethodBody("lookup", MethodTypeDesc.of(lookupType), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code.ldc(NATIVE).invokestatic(handles, "lookup", MethodTypeDesc.of(lookupType))
                                .invokestatic(handles, "privateLookupIn", MethodTypeDesc.of(lookupType,
                                        ClassDesc.of(Class.class.getName()), lookupType)).areturn()));
        var nativeLookup = MethodHandles.privateLookupIn(FloodLightCalculation.class, MethodHandles.lookup());
        Class<?> type;
        try {
            type = nativeLookup.defineClass(bytes);
        } catch (LinkageError alreadyDefined) {
            type = Class.forName(factory.packageName() + "." + factory.displayName(), false, FloodLightCalculation.class.getClassLoader());
        }
        var result = (MethodHandles.Lookup) type.getMethod("lookup").invoke(null);
        if (result.lookupClass() != FloodLightCalculation.class || !result.hasFullPrivilegeAccess())
            throw new IllegalStateException("Native lighting lookup is incompatible");
        return result;
    }
}
