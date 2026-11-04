import SwiftUI
import Charts

/// One line per calendar year (session averages, converted to 10 shots on A10),
/// with a grey bar showing the single series of sessions that have more than one.
struct TrendChart: View {
    let sessions: [Session]
    let tx: Tx

    private struct Point: Identifiable { let id = UUID(); let year: String; let date: Date; let value: Double }
    private struct Bar: Identifiable { let id = UUID(); let date: Date; let values: [Double] }

    private static let cal = Calendar(identifier: .gregorian)
    /// Same month and day in the leap year 2000, so all years share one January–December axis.
    private static func axisDate(_ d: Date) -> Date {
        let c = cal.dateComponents([.month, .day], from: d)
        return cal.date(from: DateComponents(year: 2000, month: c.month, day: c.day, hour: 12)) ?? d
    }
    private static let start = cal.date(from: DateComponents(year: 2000, month: 1, day: 1))!
    private static let end = cal.date(from: DateComponents(year: 2000, month: 12, day: 31, hour: 23))!
    private static let palette: [Color] = [Color.accentColor, .red, .blue, .orange, .purple, .teal]

    private var years: [String] { Array(Set(sessions.map(\.year))).sorted(by: >).prefix(6).map { $0 } }

    var body: some View {
        let ys = years
        let shown = sessions.filter { ys.contains($0.year) }
        let points = shown.compactMap { s in s.normalizedAverage.map { Point(year: s.year, date: Self.axisDate(s.day), value: $0) } }
            .sorted { $0.date < $1.date }
        let bars = shown.compactMap { s -> Bar? in
            let v = s.series.compactMap(\.normalized)
            return v.count > 1 ? Bar(date: Self.axisDate(s.day), values: v) : nil
        }
        let all = points.map(\.value) + bars.flatMap(\.values)
        let lo = max(0, ((all.min() ?? 0) - 3).rounded(.down)), hi = min(100, ((all.max() ?? 100) + 2).rounded(.up))

        VStack(alignment: .leading, spacing: 8) {
            Text(tx.trendYears).font(.caption).foregroundStyle(.secondary).textCase(.uppercase)
            Chart {
                ForEach(bars) { b in
                    RuleMark(x: .value("Datum", b.date), yStart: .value("Min", b.values.min() ?? 0), yEnd: .value("Max", b.values.max() ?? 0))
                        .foregroundStyle(Color.gray.opacity(0.55))
                        .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round))
                    ForEach(Array(b.values.enumerated()), id: \.offset) { _, v in
                        PointMark(x: .value("Datum", b.date), y: .value("Serie", v))
                            .symbolSize(18)
                            .foregroundStyle(Color.gray)
                    }
                }
                ForEach(points) { p in
                    LineMark(x: .value("Datum", p.date), y: .value("Resultat", p.value), series: .value("Jahr", p.year))
                        .foregroundStyle(by: .value("Jahr", p.year))
                        .lineStyle(StrokeStyle(lineWidth: p.year == ys.first ? 3 : 2))
                    PointMark(x: .value("Datum", p.date), y: .value("Resultat", p.value))
                        .foregroundStyle(by: .value("Jahr", p.year))
                        .symbolSize(p.year == ys.first ? 40 : 28)
                }
            }
            .chartForegroundStyleScale(domain: ys, range: Array(Self.palette.prefix(ys.count)))
            .chartXScale(domain: Self.start...Self.end)
            .chartYScale(domain: lo...max(hi, lo + 1))
            .chartXAxis {
                AxisMarks(values: .stride(by: .month)) { _ in
                    AxisGridLine()
                    AxisValueLabel(format: .dateTime.month(.narrow), centered: true)
                }
            }
            .chartLegend(position: .top, alignment: .leading)
            .frame(height: 220)

            VStack(alignment: .leading, spacing: 2) {
                ForEach(ys, id: \.self) { y in
                    let v = shown.filter { $0.year == y }.compactMap(\.normalizedAverage)
                    if !v.isEmpty {
                        Text("\(y) · \(tx.yearAvg(tx.score(v.reduce(0, +) / Double(v.count)), v.count))")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            if !bars.isEmpty {
                Label(tx.keySeries, systemImage: "line.3.horizontal.decrease")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}
