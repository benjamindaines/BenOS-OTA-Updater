plugins {
    id("com.android.library")
}

android {
    namespace = "com.chiller3.MdBlock"  // adjust to your package
    compileSdk = 36  // match your app module

    defaultConfig {
        minSdk = 33  // match your app module
    }
}

dependencies {
    // add any dependencies MdBlock itself needs
}
