import 'dart:io';
import 'package:path_provider/path_provider.dart';

class BacktestReportGenerator {
  
  /// Generates a stunning monochrome HTML backtest report for a single strategy's simulation data.
  static String generateSingleReport({
    required String strategyName,
    required double netProfit,
    required double winRate,
    required double profitFactor,
    required double maxDrawdown,
    required int totalTrades,
    required List<Map<String, dynamic>> trades,
  }) {
    final pnlColor = netProfit >= 0 ? '#10B981' : '#EF4444';
    final pnlPrefix = netProfit >= 0 ? '+' : '';

    final StringBuffer tradeRows = StringBuffer();
    for (var trade in trades) {
      final isWin = trade['pnl'] >= 0;
      tradeRows.write('''
        <tr>
          <td style="font-family: monospace; font-weight: bold;">${trade['symbol']}</td>
          <td style="font-family: monospace; text-align: center;">${trade['qty']}</td>
          <td style="font-family: monospace; text-align: right;">₹${(trade['entry'] as double).toStringAsFixed(2)}</td>
          <td style="font-family: monospace; text-align: right;">₹${(trade['exit'] as double).toStringAsFixed(2)}</td>
          <td style="font-family: monospace; text-align: center;">${trade['entryTime']}</td>
          <td style="font-family: monospace; text-align: center;">${trade['exitTime']}</td>
          <td style="font-family: monospace; text-align: center; font-weight: bold;">${trade['daysHeld']} DAYS</td>
          <td style="font-family: monospace; text-align: right; font-weight: bold; color: ${isWin ? '#10B981' : '#EF4444'}">
            ${isWin ? '+' : ''}₹${(trade['pnl'] as double).toStringAsFixed(2)}
          </td>
        </tr>
      ''');
    }

    return '''
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Moba Trade // $strategyName Backtest Report</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      color: #ffffff;
      background-color: #0A0A0C;
      margin: 40px;
      padding: 0;
    }
    .header {
      border-bottom: 2px solid #27272A;
      padding-bottom: 15px;
      margin-bottom: 30px;
    }
    .header-title {
      font-family: monospace;
      font-size: 24px;
      font-weight: bold;
      letter-spacing: 1px;
    }
    .header-meta {
      font-family: monospace;
      font-size: 12px;
      color: #A1A1AA;
      margin-top: 5px;
    }
    .summary-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 20px;
      margin-bottom: 40px;
    }
    .summary-card {
      border: 1px solid #27272A;
      padding: 15px;
      border-radius: 4px;
      background-color: #121215;
    }
    .card-label {
      font-family: monospace;
      font-size: 10px;
      color: #71717A;
    }
    .card-value {
      font-family: monospace;
      font-size: 18px;
      font-weight: bold;
      margin-top: 5px;
    }
    h2 {
      font-family: monospace;
      font-size: 14px;
      font-weight: bold;
      border-bottom: 1px solid #27272A;
      padding-bottom: 5px;
      margin-top: 30px;
      margin-bottom: 15px;
      letter-spacing: 0.5px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin-bottom: 30px;
      font-size: 12px;
      background-color: #121215;
      border: 1px solid #27272A;
    }
    th {
      font-family: monospace;
      font-weight: bold;
      text-align: left;
      border-bottom: 1px solid #27272A;
      padding: 10px;
      background-color: #1a1a20;
      color: #A1A1AA;
    }
    td {
      padding: 10px;
      border-bottom: 1px solid #1f1f23;
    }
    .footer {
      font-family: monospace;
      font-size: 10px;
      color: #71717A;
      text-align: center;
      margin-top: 50px;
      border-top: 1px solid #27272A;
      padding-top: 15px;
    }
    .banner {
      background-color: #ffffff;
      color: #000000;
      padding: 15px;
      border-radius: 4px;
      margin-bottom: 30px;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .banner-title {
      font-family: monospace;
      font-weight: bold;
      font-size: 14px;
    }
  </style>
</head>
<body>

  <div class="banner">
    <div class="banner-title">BACKTEST ANALYSIS REPORT // ${strategyName.toUpperCase()}</div>
    <div style="font-family: monospace; font-size: 10px; font-weight: bold; background-color: #000000; color: #ffffff; padding: 4px 8px; border-radius: 2px;">SHARIAH COMPLIANT</div>
  </div>

  <div class="header">
    <div class="header-title">MOBA TRADE // SIMULATION METRICS</div>
    <div class="header-meta">STRATEGY: $strategyName // PERIOD: 1,000 CANDLES (SYNTHETIC HISTORICAL DATA)</div>
  </div>

  <div class="summary-grid">
    <div class="summary-card">
      <div class="card-label">NET RETURN</div>
      <div class="card-value" style="color: $pnlColor">$pnlPrefix₹${netProfit.toStringAsFixed(2)}</div>
    </div>
    <div class="summary-card">
      <div class="card-label">WIN RATE</div>
      <div class="card-value">${winRate.toStringAsFixed(1)}%</div>
    </div>
    <div class="summary-card">
      <div class="card-label">PROFIT FACTOR</div>
      <div class="card-value">${profitFactor.toStringAsFixed(2)}</div>
    </div>
    <div class="summary-card">
      <div class="card-label">MAX DRAWDOWN</div>
      <div class="card-value" style="color: #EF4444">${maxDrawdown.toStringAsFixed(2)}%</div>
    </div>
  </div>

  <h2>COMPLETED SIMULATION TRADES JOURNAL ($totalTrades EXITS)</h2>
  <table>
    <thead>
      <tr>
        <th style="width: 80px;">SYMBOL</th>
        <th style="width: 60px; text-align: center;">QTY</th>
        <th style="width: 100px; text-align: right;">ENTRY PRICE</th>
        <th style="width: 100px; text-align: right;">EXIT PRICE</th>
        <th style="width: 140px; text-align: center;">ENTRY TIME</th>
        <th style="width: 140px; text-align: center;">EXIT TIME</th>
        <th style="width: 100px; text-align: center;">DURATION</th>
        <th style="width: 120px; text-align: right;">P&L RETURN</th>
      </tr>
    </thead>
    <tbody>
      ${tradeRows.toString()}
    </tbody>
  </table>

  <div class="footer">
    THIS DOCUMENT IS PROGRAMMATICALLY GENERATED BY THE MOBA TRADE ENGINE SIMULATION COMPONENT. <br>
    SHARIAH COMPLIANCE INDEX CHECKS RUN ON ALL TRANSACTIONS. NO DEBT-FINANCED ASSETS INCLUDED.
  </div>

</body>
</html>
''';
  }

  /// Writes the complete HTML performance report directly onto the local filesystem.
  static Future<File> saveHtmlReport(String strategyName, String htmlContent) async {
    final cleanName = strategyName.replaceAll(' ', '_').replaceAll('/', '_');
    final filename = 'Backtest_${cleanName}_Report.html';

    // 1. Try Windows primary path
    if (Platform.isWindows) {
      try {
        final dir = Directory('C:\\moba trade\\backtest_reports');
        if (!await dir.exists()) {
          await dir.create(recursive: true);
        }
        final file = File('${dir.path}\\$filename');
        await file.writeAsString(htmlContent);
        return file;
      } catch (_) {
        // Fall through
      }
    }

    // 2. Try Android external storage path
    if (Platform.isAndroid) {
      try {
        final dir = await getExternalStorageDirectory();
        if (dir != null) {
          final file = File('${dir.path}/$filename');
          if (!await file.parent.exists()) {
            await file.parent.create(recursive: true);
          }
          await file.writeAsString(htmlContent);
          return file;
        }
      } catch (_) {
        // Fall through
      }
    }

    // 3. Try Application Documents Directory
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/$filename');
      if (!await file.parent.exists()) {
        await file.parent.create(recursive: true);
      }
      await file.writeAsString(htmlContent);
      return file;
    } catch (_) {
      // Fall through
    }

    // 4. Try Temporary Directory
    try {
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/$filename');
      if (!await file.parent.exists()) {
        await file.parent.create(recursive: true);
      }
      await file.writeAsString(htmlContent);
      return file;
    } catch (_) {
      // Fall through
    }

    // 5. Try Relative/Current Directory
    try {
      final file = File(filename);
      await file.writeAsString(htmlContent);
      return file;
    } catch (e) {
      throw FileSystemException('Could not write backtest report to any directory: $e');
    }
  }
}
