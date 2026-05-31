import 'package:flutter/material.dart';
import 'screens/dashboard_screen.dart';

void main() {
  runApp(const MobaTradeApp());
}

class MobaTradeApp extends StatelessWidget {
  const MobaTradeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Moba Trade',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark, // Enforce gorgeous dark mode terminal aesthetic
      darkTheme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF0A0A0C), // Pure Obsidian Deep Dark
        primaryColor: Colors.white,
        colorScheme: const ColorScheme.dark(
          primary: Colors.white,
          secondary: Color(0xFFE4E4E7), // Silver Zinc-200
          surface: Color(0xFF121215), // Deep Jet Black Card
          error: Color(0xFFEF4444), // Stark Red
        ),
        textTheme: const TextTheme(
          displayLarge: TextStyle(fontFamily: 'Courier', fontSize: 32, fontWeight: FontWeight.bold, color: Colors.white),
          titleLarge: TextStyle(fontSize: 20, fontWeight: FontWeight.w600, color: Colors.white, letterSpacing: 0.5),
          bodyLarge: TextStyle(fontSize: 16, color: Color(0xFFE4E4E7)),
          bodyMedium: TextStyle(fontSize: 14, color: Color(0xFFA1A1AA)), // Zinc-400
        ),
        cardTheme: CardThemeData(
          color: const Color(0xFF121215),
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(8),
            side: const BorderSide(color: Color(0xFF27272A), width: 1.0), // Subtle Zinc border
          ),
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF0A0A0C),
          elevation: 0,
          iconTheme: IconThemeData(color: Colors.white),
          titleTextStyle: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white, letterSpacing: 1.0),
        ),
        useMaterial3: true,
      ),
      home: const DashboardScreen(),
    );
  }
}
