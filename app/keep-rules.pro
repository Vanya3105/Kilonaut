# Kotlin's multi-file classes (SequencesKt and its parts) must stay in one package: moved apart,
# a part becomes inaccessible to the class extending it and the release build crashes on start
# (seen once Firebase came in).
-keeppackagenames kotlin.**

# Credential Manager finds its Play services provider by reflection (Google's recommended rule).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
