import pandas as pd
import numpy as np
import os

def analyze_uwb_log(filepath):
    print(f"========== 解析結果: {os.path.basename(filepath)} ==========")
    
    # データの読み込み
    try:
        df = pd.read_csv(filepath)
    except Exception as e:
        print(f"ファイル読み込みエラー: {e}")
        return

    if 'timestamp_ms' not in df.columns or 'anchor_addr' not in df.columns:
        print("エラー: 必要なカラム (timestamp_ms, anchor_addr) が見つかりません。")
        return

    # 全体の統計
    df = df.sort_values('timestamp_ms')
    total_time_ms = df['timestamp_ms'].max() - df['timestamp_ms'].min()
    print(f"▶ 総合記録時間: {total_time_ms / 1000:.2f} 秒")
    print(f"▶ 総パケット数: {len(df)} 件")
    
    # Anchorごとの統計
    print("\n▶ Anchor別 通信ステータス")
    anchors = df['anchor_addr'].unique()
    for a in sorted(anchors):
        a_df = df[df['anchor_addr'] == a]
        count = len(a_df)
        
        # 到着間隔（Delta T）の計算
        intervals = a_df['timestamp_ms'].diff().dropna()
        mean_int = intervals.mean() if not intervals.empty else 0
        max_int = intervals.max() if not intervals.empty else 0
        min_int = intervals.min() if not intervals.empty else 0
        
        print(f"  [Anchor {a}]")
        print(f"    - パケット数: {count} 件 ({count / (total_time_ms/1000):.1f} Hz)")
        print(f"    - 平均到着間隔: {mean_int:.1f} ms")
        print(f"    - 最大沈黙時間: {max_int:.1f} ms (最短: {min_int:.1f} ms)")

    # タイムウィンドウのシミュレーション
    print("\n▶ タイムウィンドウ シミュレーション (全Anchorが揃う確率)")
    windows = [100, 200, 300, 500, 1000] # ミリ秒
    expected_anchor_count = len(anchors)
    
    for w in windows:
        # timestamp をウィンドウサイズで割ってグループ化
        df['window_id'] = (df['timestamp_ms'] - df['timestamp_ms'].min()) // w
        
        # 各ウィンドウに存在するユニークなAnchor数をカウント
        window_counts = df.groupby('window_id')['anchor_addr'].nunique()
        total_windows = len(window_counts)
        
        # 全Anchorが揃っているウィンドウの数
        valid_windows = (window_counts == expected_anchor_count).sum()
        valid_ratio = (valid_windows / total_windows) * 100 if total_windows > 0 else 0
        
        print(f"  - ウィンドウ幅 {w:>4} ms: 成功 {valid_windows:>4} / 全 {total_windows:>4} 枠 ({valid_ratio:>5.1f} %)")
    print("========================================================\n")


# 実行例：ファイルのパスを指定して実行してください
# /Users/kotaroikunishi/Downloads/nishio_labo/ichi/FP/26_0531/data_test
analyze_uwb_log("/Users/kotaroikunishi/Downloads/nishio_labo/ichi/FP/26_0531/data_test/trial1_7_5.csv")
# analyze_uwb_log("trial2_0_0.csv")
# analyze_uwb_log("trial3_0_0.csv")