yaml
name: Build APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Accept SDK licenses and install build tools
        run: |
          yes | sdkmanager --licenses || true
          sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.5'

      - name: Build debug APK
        run: gradle assembleDebug --no-daemon --stacktrace

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: uziel-barber-apk
          path: app/build/outputs/apk/debug/app-debug.apk
גלול למטה ולחץ על הכפתור הירוק "Commit changes...", ואז שוב "Commit changes" בחלון שנפתח.

תגיד לי "בוצע" ונעבור לקובץ הבא (יהיה קצר וקל, רק 3-4 שורות).

You are out of free messages until 6:40 PM

קוד

בעיותbuild-apk.yml

Claude is AI and can make mistakes. Please double-check responses.
