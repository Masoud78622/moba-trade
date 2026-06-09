import 'broker_service.dart';
import 'secure_storage_service.dart';


class PortfolioStatus {
  final double cashBalance;
  final double holdingsValue;
  final double activePositionsValue;
  final double totalPortfolioValue;
  final bool isSafeguardActive;
  final int activePositionsCount;

  PortfolioStatus({
    required this.cashBalance,
    required this.holdingsValue,
    required this.activePositionsValue,
    required this.totalPortfolioValue,
    required this.isSafeguardActive,
    required this.activePositionsCount,
  });
}

class PortfolioRiskEngine {
  /// Aggregates balances from the current active broker service.
  static Future<PortfolioStatus> getPortfolioStatus() async {
    double cash = 0.0;
    double holdingsVal = 0.0;
    double activePositionsVal = 0.0;

    try {
      cash = await BrokerService.current.getMarginCapital();
    } catch (_) {}

    try {
      final holdings = await BrokerService.current.fetchSwingHoldings();
      for (var h in holdings) {
        final double qty = (h['qty'] as num?)?.toDouble() ?? 0.0;
        final double currentPrice = (h['current'] as num?)?.toDouble() ?? (h['entry'] as num?)?.toDouble() ?? 0.0;
        holdingsVal += qty * currentPrice;
      }
    } catch (_) {}

    int activeCount = 0;
    try {
      final activePos = await BrokerService.current.fetchActivePositions();
      activeCount = activePos.length;
      for (var p in activePos) {
        final double qty = (p['qty'] as num?)?.toDouble() ?? 0.0;
        final double currentPrice = (p['current'] as num?)?.toDouble() ?? (p['entry'] as num?)?.toDouble() ?? 0.0;
        activePositionsVal += qty * currentPrice;
      }
    } catch (_) {}

    final total = cash + holdingsVal + activePositionsVal;
    final isSafeguardEnabled = await SecureStorageService.readSafeguardEnabled();
    final safeguardActive = isSafeguardEnabled && (total < 100000.0);

    return PortfolioStatus(
      cashBalance: cash,
      holdingsValue: holdingsVal,
      activePositionsValue: activePositionsVal,
      totalPortfolioValue: total,
      isSafeguardActive: safeguardActive,
      activePositionsCount: activeCount,
    );
  }

  /// Calculates the dynamic swing stop-loss threshold percentage based on account size.
  /// - Under ₹1L: -5.0%
  /// - ₹1L to ₹5L: -7.0%
  /// - Over ₹5L: -10.0%
  static double calculateSwingStopLossThreshold(double totalPortfolioValue) {
    if (totalPortfolioValue < 100000.0) {
      return -5.0;
    } else if (totalPortfolioValue <= 500000.0) {
      return -7.0;
    } else {
      return -10.0;
    }
  }

  /// Calculates target trade size allocation based on strategy win rate and total portfolio value.
  static double calculateTargetTradeAllocation({
    required double winRate,
    required double totalPortfolioValue,
  }) {
    double allocationFactor = 0.05; // Default 5% for high-risk / low-win-rate (< 60%)
    double maxCap = 10000.00;

    if (winRate >= 70.0) {
      allocationFactor = 0.15; // 15% allocation for high-win-rate (>= 70%)
      maxCap = 20000.00;
    } else if (winRate >= 60.0) {
      allocationFactor = 0.10; // 10% allocation for medium-win-rate (60% - 70%)
      maxCap = 15000.00;
    }

    final calculatedSize = totalPortfolioValue * allocationFactor;
    return calculatedSize > maxCap ? maxCap : calculatedSize;
  }

  /// Calculates order quantity ensuring available cash is not breached (delivery basis long only compliance).
  static int calculateOrderQuantity({
    required double targetAllocation,
    required double currentPrice,
    required double availableCash,
  }) {
    if (currentPrice <= 0.0) return 0;
    final maxTradeCapital = targetAllocation > availableCash ? availableCash : targetAllocation;
    return (maxTradeCapital / currentPrice).floor();
  }
}
