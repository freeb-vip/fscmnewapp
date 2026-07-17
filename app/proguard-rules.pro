# Project-specific R8 rules.
# ML Kit discovers these registrars from AndroidManifest metadata and creates them by reflection.
# The firebase-components consumer rule keeps the class names, but R8 can still remove constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
    public *;
}

# jose4j uses the SLF4J API and falls back to a no-op logger when no binding is packaged.
-dontwarn org.slf4j.impl.StaticLoggerBinder
