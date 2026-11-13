# docscan-ocr — 이 모듈을 의존하면 소비 앱의 R8 에 자동 적용된다.
#
# 이 모듈의 코드는 리플렉션·직렬화를 쓰지 않는다.
# ML Kit 텍스트 인식은 매니페스트 meta-data 의 등록자(TextRegistrar 등)를 리플렉션으로 기본 생성자 호출해 만든다.
# firebase-components 16.1.0 규칙만으로는 R8 full mode 에서 생성자가 지워져 release 에서 "NoSuchMethodException <init> []" 가 난다.
# docscan 모듈과 같은 규칙이다 — docscan 없이 이 모듈 규칙만 읽히는 경우에도 빠지지 않게 여기에도 둔다.
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar
