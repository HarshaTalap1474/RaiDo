# 🚖 Raido - Ride Sharing App Prototype

A real-time ride-sharing Android application prototype built for the BE EnTC Final Year Project. This app enables users to request rides and drivers to accept them using live geolocation tracking.

## 📱 Features
* **User Authentication:** Secure Login & Registration using Firebase Auth.
* **Real-Time Database:** Instant ride requests and status updates using Firebase Realtime Database.
* **Geolocation:** * Live user location tracking using Google Fused Location API.
    * Interactive maps powered by OpenStreetMap (OSM) & osmdroid.
* **Two-User Modes:** Single app supports switching between "Rider" and "Driver" modes.

## 🛠 Tech Stack
* **Language:** Java
* **IDE:** Android Studio Iguana/Jellyfish
* **Backend:** Firebase (Auth, Realtime Database)
* **Maps:** OpenStreetMap (osmdroid)
* **Location:** Google Play Services Location

## 🚀 Setup Instructions

1.  **Clone the Repo:**
    ```bash
    git clone [https://github.com/HarshaTalap1474/Raido.git](https://github.com/HarshaTalap1474/Raido.git)
    ```
2.  **Firebase Setup:**
    * Create a project on [Firebase Console](https://console.firebase.google.com/).
    * Enable **Authentication** (Email/Password).
    * Enable **Realtime Database** (Test Rules: `true`).
    * Download `google-services.json` and place it in the `app/` folder.
3.  **Build:**
    * Open the project in Android Studio.
    * Sync Gradle files.
    * Run on an Emulator or Physical Device.

## 📸 Screenshots
*..*

## 📄 License
This project is created for educational purposes by Harshavardhan Talap.