plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.shop"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmcshop"
}

dependencies {
    // PlaceholderExpansion 은 추상 클래스라 Proxy 로 못 만든다. compileOnly 가 필요하다.
    compileOnly(libs.placeholderapi) { isTransitive = false }

    // JDBC 드라이버는 들고 가지 않는다 — Paper 가 서버 라이브러리로 갖고 있다. 테스트만 따로.
    testImplementation(libs.sqlite.jdbc)
}
