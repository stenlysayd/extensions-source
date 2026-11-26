import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "3600000 Beauty"
    versionCode = 2004
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        baseUrl = "https://3600000.xyz"
        lang = "all"
    }

    deeplink {
        path("/..*")
    }
}
