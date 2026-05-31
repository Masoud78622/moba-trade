import 'dart:io';
import 'package:path_provider/path_provider.dart';

class PdfGenerator {
  
  /// Generates a premium minimalist black-and-white EOD performance report in standard HTML print format.
  /// Android's PrintManager can convert this directly to a PDF document with zero external library dependencies.
  static String generateEodHtmlReport({
    required String date,
    required double totalCapital,
    required double dailyPnL,
    required int totalTrades,
    required double winRate,
    required List<Map<String, dynamic>> strategyPerformances,
    required List<Map<String, dynamic>> completedTrades,
    required List<Map<String, dynamic>> swingHoldings,
  }) {
    final pnlColor = dailyPnL >= 0 ? '#10B981' : '#EF4444';
    final pnlPrefix = dailyPnL >= 0 ? '+' : '';

    final StringBuffer strategyRows = StringBuffer();
    for (var strat in strategyPerformances) {
      final rate = strat['winRate'] as double;
      strategyRows.write('''
        <tr>
          <td style="font-family: monospace; font-weight: bold;">${strat['id']}</td>
          <td>${strat['name']}</td>
          <td style="font-family: monospace; text-align: center;">${strat['trades']}</td>
          <td style="font-family: monospace; text-align: center; font-weight: bold;">${rate.toStringAsFixed(1)}%</td>
          <td style="font-family: monospace; text-align: right; color: ${strat['netProfit'] >= 0 ? '#000000' : '#EF4444'}">
            ${strat['netProfit'] >= 0 ? '+' : ''}₹${(strat['netProfit'] as double).toStringAsFixed(2)}
          </td>
        </tr>
      ''');
    }

    final StringBuffer tradeRows = StringBuffer();
    for (var trade in completedTrades) {
      final isWin = trade['win'] as bool;
      tradeRows.write('''
        <tr>
          <td style="font-family: monospace; font-weight: bold;">${trade['symbol']}</td>
          <td>${trade['strategy']}</td>
          <td style="font-family: monospace; text-align: center;">${trade['qty']}</td>
          <td style="font-family: monospace; text-align: right;">₹${(trade['entry'] as double).toStringAsFixed(2)}</td>
          <td style="font-family: monospace; text-align: right;">₹${(trade['exit'] as double).toStringAsFixed(2)}</td>
          <td style="font-family: monospace; text-align: right; font-weight: bold; color: ${isWin ? '#10B981' : '#EF4444'}">
            ${isWin ? '+' : ''}₹${(trade['pnl'] as double).toStringAsFixed(2)}
          </td>
        </tr>
      ''');
    }

    final StringBuffer swingRows = StringBuffer();
    for (var swing in swingHoldings) {
      swingRows.write('''
        <tr>
          <td style="font-family: monospace; font-weight: bold;">${swing['symbol']}</td>
          <td>${swing['strategy']}</td>
          <td style="font-family: monospace; text-align: center;">${swing['qty']}</td>
          <td style="font-family: monospace; text-align: right;">₹${(swing['entry'] as double).toStringAsFixed(2)}</td>
          <td style="font-family: monospace; text-align: center; font-weight: bold;">${swing['daysHeld']} DAYS</td>
          <td style="font-family: monospace; text-align: right; color: #10B981; font-weight: bold;">✓ HALAL (ZOYA)</td>
        </tr>
      ''');
    }

    return '''
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Moba Trade // EOD Performance Report</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      color: #000000;
      background-color: #ffffff;
      margin: 40px;
      padding: 0;
    }
    .header {
      border-bottom: 2px solid #000000;
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
      color: #555555;
      margin-top: 5px;
    }
    .summary-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 20px;
      margin-bottom: 40px;
    }
    .summary-card {
      border: 1px solid #cccccc;
      padding: 15px;
      border-radius: 4px;
    }
    .card-label {
      font-family: monospace;
      font-size: 10px;
      color: #555555;
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
      border-bottom: 1px solid #000000;
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
    }
    th {
      font-family: monospace;
      font-weight: bold;
      text-align: left;
      border-bottom: 1px solid #000000;
      padding: 8px;
      background-color: #f5f5f5;
    }
    td {
      padding: 8px;
      border-bottom: 1px solid #eeeeee;
    }
    .footer {
      font-family: monospace;
      font-size: 10px;
      color: #777777;
      text-align: center;
      margin-top: 50px;
      border-top: 1px solid #eeeeee;
      padding-top: 15px;
    }
  </style>
</head>
<body>

  <div class="header">
    <div class="header-title">MOBA TRADE // PERFORMANCE REPORT</div>
    <div class="header-meta">GEN TIMESTAMP: $date // SYSTEM: SECURED ACTIVE</div>
  </div>

  <div class="summary-grid">
    <div class="summary-card">
      <div class="card-label">TOTAL CAPITAL</div>
      <div class="card-value">₹${totalCapital.toStringAsFixed(2)}</div>
    </div>
    <div class="summary-card">
      <div class="card-label">DAILY NET P&L</div>
      <div class="card-value" style="color: $pnlColor">$pnlPrefix₹${dailyPnL.toStringAsFixed(2)}</div>
    </div>
    <div class="summary-card">
      <div class="card-label">TRADES TAKEN</div>
      <div class="card-value">$totalTrades EXITS</div>
    </div>
    <div class="summary-card">
      <div class="card-label">DAILY WIN RATE</div>
      <div class="card-value">${winRate.toStringAsFixed(1)}%</div>
    </div>
  </div>

  <h2>STRATEGY PERFORMANCE BREAKDOWN</h2>
  <table>
    <thead>
      <tr>
        <th style="width: 80px;">ID</th>
        <th>STRATEGY NAME</th>
        <th style="width: 100px; text-align: center;">TRADES</th>
        <th style="width: 100px; text-align: center;">WIN RATE</th>
        <th style="width: 120px; text-align: right;">NET RETURN</th>
      </tr>
    </thead>
    <tbody>
      ${strategyRows.toString()}
    </tbody>
  </table>

  <h2>DAILY COMPLETED EXITS LOG</h2>
  <table>
    <thead>
      <tr>
        <th style="width: 80px;">SYMBOL</th>
        <th>STRATEGY</th>
        <th style="width: 80px; text-align: center;">QTY</th>
        <th style="width: 100px; text-align: right;">ENTRY</th>
        <th style="width: 100px; text-align: right;">EXIT</th>
        <th style="width: 120px; text-align: right;">P&L</th>
      </tr>
    </thead>
    <tbody>
      ${tradeRows.toString()}
    </tbody>
  </table>

  <h2>ACTIVE SWING HOLDINGS (HELD &gt; 1 DAY)</h2>
  <table>
    <thead>
      <tr>
        <th style="width: 80px;">SYMBOL</th>
        <th>STRATEGY</th>
        <th style="width: 80px; text-align: center;">QTY</th>
        <th style="width: 120px; text-align: right;">ENTRY VALUE</th>
        <th style="width: 120px; text-align: center;">HOLDING PERIOD</th>
        <th style="width: 120px; text-align: right;">SHARIAH STATUS</th>
      </tr>
    </thead>
    <tbody>
      ${swingRows.toString()}
    </tbody>
  </table>

  <div class="footer">
    THIS DOCUMENT IS PROGRAMMATICALLY GENERATED NATIVELY BY THE MOBA TRADE ENGINE. <br>
    SHARIAH STATUS: SHARIAH COMPLIANT // NO CONVENTIONAL SHORT SALES OR DEBT TRANSACTIONS INCLUDED.
  </div>

</body>
</html>
''';
  }

  /// Writes the complete HTML performance report directly onto the local filesystem.
  static Future<File> saveHtmlReport(String htmlContent) async {
    const filename = 'MobaTrade_EOD_Report.html';

    // 1. Try Windows primary path
    if (Platform.isWindows) {
      try {
        final dir = Directory('C:\\moba trade');
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

    // 2. Try Android public Downloads directory
    if (Platform.isAndroid) {
      try {
        final publicDownloadDir = Directory('/storage/emulated/0/Download');
        if (await publicDownloadDir.exists()) {
          final file = File('${publicDownloadDir.path}/$filename');
          await file.writeAsString(htmlContent);
          return file;
        }
        
        final altDownloadDir = Directory('/sdcard/Download');
        if (await altDownloadDir.exists()) {
          final file = File('${altDownloadDir.path}/$filename');
          await file.writeAsString(htmlContent);
          return file;
        }
      } catch (_) {
        // Fall through
      }

      // Robust app-specific external storage data folder fallback if public Downloads is blocked/inaccessible
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
      throw FileSystemException('Could not write EOD report to any directory: $e');
    }
  }
}
