# DiskUsage for Android

[![Android](https://img.shields.io/badge/Platform-Android-green.svg?style=flat-square&logo=android)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Material 3](https://img.shields.io/badge/Design-Material_3-blue.svg?style=flat-square&logo=material-design)](https://m3.material.io)
[![License](https://img.shields.io/badge/License-GPL%20v2-orange.svg?style=flat-square)](COPYING.txt)
[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat-square)](https://android-arsenal.com/api?level=21)

**DiskUsage** is a powerful utility for Android that provides a visual representation of your storage, allowing you to quickly identify and manage space-consuming files and directories.

---

## 🚀 The Migration Journey

This repository represents a modern evolution of the classic DiskUsage application. We have undergone a significant technical overhaul to bring the tool into the modern Android ecosystem:

- **Original Version**: Inherited from the legendary [IvanVolosyuk/diskusage](https://github.com/IvanVolosyuk/diskusage).
- **Intermediate**: Build upon improvements from [WhiredPlanck/diskusage](https://github.com/WhiredPlanck/diskusage).
- **Modern Era**: 
  - **Full Kotlin Migration**: Rewritten from Java to Kotlin for better performance, safety, and maintainability.
  - **Material 3 UI**: Completely redesigned using **Jetpack Compose** and Material 3 guidelines, providing a sleek, modern, and responsive user experience.
  - **Modern SDKs**: Updated to target Android 14 (API 34) while maintaining compatibility down to API 21.

---

## ✨ Key Features

- **Proportional Visualization**: Directories are displayed in a nested diagram where size is relative to their storage consumption.
- **Deep Exploration**: Navigate through multiple levels of subdirectories with intuitive zoom and pan gestures.
- **Precision Cleanup**: Identify "space hogs" at a glance and take action to free up storage.
- **Fast Scanning**: Optimized core engine for rapid storage analysis.
- **Modern UI**: Full Dark Mode support and dynamic color integration (Material You).

---

## 📸 Screenshots

| Modern Material 3 UI | Legacy Inspiration |
| :---: | :---: |
| *Scanning & Visualization* | ![Ancient Version](extra/screenshot.png) |
| (Stay tuned for updated visuals) | (The classic look that started it all) |

---

## 🛠 Building the Project

This project uses the Gradle build system. To build the application:

1. Clone the repository:
   ```bash
   git clone https://github.com/WhiredPlanck/diskusage.git
   ```
2. Open in **Android Studio** (Koala or newer recommended).
3. Build the project using the standard Gradle sync.

---

## 📄 License

This project is licensed under the **GNU General Public License v2**. See the [COPYING.txt](COPYING.txt) file for details.

---

## 🙏 Credits & Appreciation

- **Ivan Volosyuk**: The original creator of the DiskUsage app.
- **WhiredPlanck**: For paving the way with significant enhancements.
- **The Community**: For years of support and feedback on one of Android's most essential utilities.

---
*Built with ❤️ using Kotlin and Jetpack Compose.*
