# docscan — 이 모듈을 의존하면 소비 앱의 R8 에 자동 적용된다.
#
# 이 모듈의 코드는 리플렉션·직렬화를 쓰지 않는다. Parcelable 결과(DocumentScanResult·PageList 등)는 kotlin-parcelize 가
# 만든 CREATOR 를 쓴다 — AGP 기본 규칙에도 있지만, 기본 파일을 쓰지 않는 앱을 위해 이 SDK 패키지에 한해 둔다.
-keepclassmembers class net.plumt.docscan.** implements android.os.Parcelable {
    public static final ** CREATOR;
}
#
# ML Kit 은 매니페스트 meta-data 에 적힌 등록자(CommonComponentRegistrar 등)를 리플렉션으로 기본 생성자 호출해 만든다.
# ML Kit 이 끌어오는 firebase-components 16.1.0 의 규칙은 `-keep class * implements ComponentRegistrar` 뿐이라(생성자 미보존)
# R8 full mode(AGP 8+ 기본)에서 생성자가 지워지고 release 에서 "NoSuchMethodException <init> []" 로 초기화가 실패한다.
# firebase-components 19.0.0 이 고쳐 실은 규칙과 같은 것을 둔다 — 생성자만 보존하므로 축소 대가가 거의 없다.
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar
#
# 넓은 -keep(라이브러리 전체 보존)이나 -dontwarn com.google.android.gms.** 는 두지 않는다 — 소비 앱의 축소를 막아 APK 가 커지고,
# 소비 앱 자신의 누락 경고까지 숨긴다. FileProvider 하위 클래스는 매니페스트에 있어 AAPT 규칙이 보존한다.
