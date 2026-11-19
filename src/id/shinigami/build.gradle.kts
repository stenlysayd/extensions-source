import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Shinigami"
    versionCode = 86
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    deeplink {
        host("shinigami.asia")
        host("*.shinigami.asia")
        host("shinigami.mx")
        host("*.shinigami.mx")
        path("/series/..*")
    }

    source {
        lang = "id"
        baseUrl {
            custom("https://s1.shinigami.mx")
        }
        id = 3411809758861089969L
    }
}
