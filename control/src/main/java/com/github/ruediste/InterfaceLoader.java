package com.github.ruediste;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.classgraph.*;

public class InterfaceLoader {

    @FunctionalInterface
    public interface FieldWriter {
        void write(Object obj, OutputStream out) throws IOException;
    }

    @FunctionalInterface
    public interface FieldReader {
        Object read(InputStream in) throws IOException;
    }

    public static class InterfaceField {
        public String name;
        public String cType;
        public Field field;
        public FieldWriter writer;
        public FieldReader reader;

        public void setType(String cType, FieldWriter writer, FieldReader reader) {
            this.cType = cType;
            this.writer = writer;
            this.reader = reader;
        }
    }

    public static class InterfaceClass {
        public String simpleName;
        public int id;
        public List<InterfaceField> fields = new ArrayList<>();
        public Class<?> cls;
    }

    public static List<InterfaceClass> load() {
        var result = new ArrayList<InterfaceClass>();
        try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptPackages(App.class.getPackageName())
                .scan()) {
            var classInfos = scanResult.getClassesImplementing(InterfaceMessage.class).stream()
                    .filter(x -> !x.isAbstract())
                    .sorted(Comparator.comparing(x -> x.getSimpleName()))
                    .toList();
            int id = 0;
            for (ClassInfo info : classInfos) {
                var cls = new InterfaceClass();
                cls.cls = info.loadClass();
                cls.simpleName = info.getSimpleName();
                cls.id = id++;
                result.add(cls);
                for (var field : info.getFieldInfo()) {
                    var typeFound = false;
                    var interfaceField = new InterfaceField();
                    interfaceField.name = field.getName();
                    interfaceField.field = field.loadClassAndGetField();
                    cls.fields.add(interfaceField);
                    if (field.getTypeDescriptorStr().equals("F")) {
                        typeFound = true;
                        interfaceField.setType("float", (obj, out) -> {
                            var value = Float.floatToIntBits((Float) obj);
                            out.write(value);
                            out.write(value >> 8);
                            out.write(value >> 16);
                            out.write(value >> 24);
                        }, in -> {
                            int value = in.read() | in.read() << 8 | in.read() << 16 | in.read() << 24;
                            return Float.intBitsToFloat(value);
                        });
                    }
                    if (field.getTypeDescriptorStr().equals("Z")) {
                        typeFound = true;
                        interfaceField.setType("bool", (obj, out) -> {
                            boolean value = (boolean) obj;
                            out.write(value ? 1 : 0);
                        }, in -> {
                            int value = in.read();
                            return value == 1;
                        });
                    }
                    for (var annotation : field.getAnnotationInfo()) {
                        if (typeFound) {
                            throw new RuntimeException(
                                    "Multiple annotations found on " + info.getName() + "." + field.getName());
                        }

                        if (Datatype.uint16.class.getName().equals(annotation.getName())) {
                            interfaceField.setType("uint16", (obj, out) -> {
                                var value = (Integer) obj;
                                out.write(value);
                                out.write(value >> 8);
                            }, in -> {
                                return in.read() | in.read() << 8;
                            });
                        } else {
                            throw new RuntimeException("Unknown annotation " + annotation.getName() + " on "
                                    + info.getName() + "." + field.getName());
                        }
                        typeFound = true;
                    }
                    if (!typeFound) {
                        throw new RuntimeException(
                                "No type annotation found and no known type: " + info.getName() + "."
                                        + field.getName() + " type: " + field.getTypeDescriptorStr());
                    }
                }
            }
        }
        return result;
    }
}
