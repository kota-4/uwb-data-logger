// 25/12/16 kota remake version
// firmwareの src/application/application_definitions.h のパケットサイズ定義に基づく設計に変更

// 1. CIRのデータ点数の定義
// ファイル: src/application/application_definitions.h 12行目:
// #define UWB_CIR_SIZE		    128			//created by lin in 2023/12/04

// 2. ファイル: src/application/dw_main.c 355行目付近 & 382行目付近:
// // 355行目: 配列の確保
// uint8 cir[UWB_CIR_SIZE * 4]; // 128 * 4 = 512バイト
// // ... (中略) ...
// // 382行目: USB送信バッファへのコピー
// memcpy(&usbdataseq[n], &cir[0], UWB_CIR_SIZE * 4);
// send_usbmessage(&usbdataseq[0], n + UWB_CIR_SIZE * 4);
// より, サイズ：128 * 4 = 512バイト

// 3. ヘッダーのサイズ計算
// ID(64bit)=(16文字 + 空白1) * 3 + 数値(32bit)=(8文字 + 空白1) * 5 = 96文字

// final. 固定長ロジックの値
// ヘッダー=96バイト + CIR=512バイト = 合計608バイト
// ヘッダーを見つけたら, そこから608バイトを1パケットとして処理するロジックに変更
//  (既存versionでは, 改行コードをパケット終端としていた)


package application;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.fazecast.jSerialComm.SerialPort;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

// インポート文に追加
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UWB Data Logger Controller (Final Stable Version)
 * Features:
 * 1. Fixed-length packet reading (608 bytes) to prevent CIR corruption.
 * 2. Polling-based reading thread for macOS stability.
 * 3. Priority detection for 'tty.' ports to ensure correct connection.
 */
public class OnlineMainController {
    
    @FXML
    private Button selectDirButton;

    private String sessionId;  // セッション通してユニーク

    @FXML
    private TextField FileName;

    @FXML
    private ChoiceBox<String> soundChoiceBox;
    @FXML
    private ChoiceBox<String> fileChoiceBox;

    @FXML
    private TextField TimeRange;
    private int selectedTimeRange = 60; // Default 60 seconds
    private Timer timer;

    private File selectedDirectory;
    private String selectedDirectoryName = "NewFile";

    private enum Sound { ON, OFF }
    Sound selectedSound = Sound.ON;

    private enum FileFormat { CSV, JSON, NONE }
    FileFormat selectedFormat = FileFormat.NONE; // ★デフォルトをNONE(保存なし)に設定

    private List<PacketData> packetDataList = new ArrayList<>();
    private SerialPort port;
    
    // ■■■■■ 定数定義 (Firmware仕様に基づく) ■■■■■
    private static final int MAGIC_SIZE = 0;
    private static final int HEADER_SIZE = 96;
    private static final int CIR_SIZE = 512;
    private static final int TOTAL_PACKET_SIZE = MAGIC_SIZE + HEADER_SIZE + CIR_SIZE; // 608 bytes

    // ▼▼▼ オンライン送信用に追加 ▼▼▼
    private ConcurrentHashMap<String, double[]> apiBuffer = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Integer> anchorPacketCount = new ConcurrentHashMap<>();
    private Timer apiTimer;
    private static final String API_URL = "http://localhost:8000/api/predict";
    // ▲▲▲ ▲▲▲

    @FXML
    private void initialize() {
        soundChoiceBox.setItems(FXCollections.observableArrayList("音ON", "音OFF"));
        
        // ★ ドロップダウンは明示的な保存用のみ（未選択ならNONEのまま）
        fileChoiceBox.setItems(FXCollections.observableArrayList("csv", "json"));

        soundChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) handleSoundChoiceChange(newValue);
        });

        fileChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) handleFileFormatSelect(newValue);
        });
    }
    
    @FXML
    private void handleDDButtonClick(ActionEvent event) {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("保存先ディレクトリの選択");
        Stage stage = (Stage) selectDirButton.getScene().getWindow();
        selectedDirectory = dirChooser.showDialog(stage);
        if (selectedDirectory != null) {
            System.out.println("Directory selected: " + selectedDirectory);
        }
    }
    
    @FXML
    private void getFileName(ActionEvent event) {
        selectedDirectoryName = FileName.getText();
        System.out.println("保存ファイル名：" + selectedDirectoryName);
    }
    
    private void handleSoundChoiceChange(String value) {
        selectedSound = "音ON".equals(value) ? Sound.ON : Sound.OFF;
    }
    
    private void handleFileFormatSelect(String value) {
        if ("csv".equals(value)) {
            selectedFormat = FileFormat.CSV;
        } else if ("json".equals(value)) {
            selectedFormat = FileFormat.JSON;
        } else {
            selectedFormat = FileFormat.NONE; 
        }
    }

    // ■■■■■ 通信開始処理 (ポーリング方式・固定長切り出し・tty優先) ■■■■■
    @FXML
    private void handleOpenButtonClick() {
        // ★ 修正: 保存モードが選ばれている時だけ、入力必須チェックを行う
        if (selectedFormat != FileFormat.NONE) {
            if (selectedDirectory == null || selectedDirectory.getAbsolutePath().trim().isEmpty() || 
                selectedDirectoryName == null || selectedDirectoryName.trim().isEmpty()) {
                showAlert("エラー", "保存形式が選択されています。保存先ディレクトリとファイル名を入力してください。");
                return;
            }
        }

        sessionId = java.util.UUID.randomUUID().toString();
        System.out.println("Session ID: " + sessionId);

        // ★ 追加: 時間入力の動的読み取り（空欄なら0＝無制限）
        String timeText = TimeRange.getText();
        if (timeText == null || timeText.trim().isEmpty()) {
            selectedTimeRange = 0; 
        } else {
            try {
                selectedTimeRange = Integer.parseInt(timeText);
            } catch (NumberFormatException e) {
                showAlert("エラー", "時間には数値を入力するか、空欄にしてください。");
                return;
            }
        }

        // ★ Step1: リセット用に一時オープン → 0xFF送信 → 即クローズ
        SerialPort resetPort = findStm32Port();
        if (resetPort == null) {
            showAlert("エラー", "STM32 Virtual ComPortが見つかりません。");
            return;
        }
        resetPort.setBaudRate(115200);
        resetPort.setNumDataBits(8);
        resetPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        resetPort.setParity(SerialPort.NO_PARITY);

        if (resetPort.openPort()) {
            try {
                System.out.println("[RESET] リセットコマンド送信...");
                Thread.sleep(300);
                resetPort.writeBytes(new byte[]{(byte) 0xFF}, 1);
                Thread.sleep(200);
            } catch (InterruptedException e) {
                System.err.println("[RESET] 待機中断: " + e.getMessage());
            } finally {
                resetPort.closePort();
                System.out.println("[RESET] ポートクローズ、FW再起動待機中...");
            }
        }

        // ★ Step2+3: ポートが再出現するまでリトライ（最大10秒）
        System.out.println("[RESET] ポート再出現を待機中...");
        port = null;
        for (int retry = 0; retry < 20; retry++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            port = findStm32Port();
            if (port != null) {
                System.out.println("[RESET] ポート再出現確認（" + (retry + 1) * 500 + "ms後）: " + port.getSystemPortName());
                break;
            }
            System.out.println("[RESET] 待機中... (" + (retry + 1) + "/20)");
        }

        if (port == null) {
            showAlert("エラー", "リセット後にSTM32 Virtual ComPortが見つかりません。");
            return;
        }

        // 通信設定 (115200, 8N1)
        port.setBaudRate(115200);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 100, 0);

        if (port.openPort()) {
            System.out.println("Auto-selected UWB port: " + port.getSystemPortName());
            System.out.println("Port opened successfully.");

            port.setDTR();
            port.setRTS();

            System.out.println("[WAIT] TDMA同期待機中（3秒）...");
            try { Thread.sleep(3000); } catch (InterruptedException e) {}
            System.out.println("[WAIT] 受信開始");

            // 受信スレッド開始
            Thread readerThread = new Thread(this::packetReadingLoop);
            readerThread.setDaemon(true);
            readerThread.start();

            // ★ 修正: 5fps (200ms間隔) でAPIへ送信
            apiTimer = new Timer();
            apiTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    sendBufferToApi();
                }
            }, 1000, 200);

            // ★ 修正: 終了タイマー（無制限対応）
            if (selectedTimeRange > 0) {
                timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        javafx.application.Platform.runLater(() -> handleCloseButtonClick(null));
                    }
                }, selectedTimeRange * 1000L);
                System.out.println("[Timer] " + selectedTimeRange + "秒後に自動終了します");
            } else {
                System.out.println("[Timer] 時間無制限モード（手動でCloseを押すまで継続します）");
            }

        } else {
            System.out.println("Failed to open port.");
            showAlert("エラー", "ポートを開けませんでした。");
        }
    }

    private SerialPort findStm32Port() {
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort p : ports) {
            String systemName = p.getSystemPortName();
            String desc = p.getDescriptivePortName();
            if (systemName.startsWith("tty.") && desc != null && 
                desc.contains("STM32 Virtual ComPort")) {
                return p;
            }
        }
        for (SerialPort p : ports) {
            String desc = p.getDescriptivePortName();
            if (desc != null && desc.contains("STM32 Virtual ComPort")) {
                return p;
            }
        }
        return null;
    }

    private void packetReadingLoop() {
        Thread.currentThread().setName("UWB-SerialReader");
        List<Byte> persistentBuffer = new ArrayList<>();
        byte[] readBuffer = new byte[1024];
        long lastHeartbeat = System.currentTimeMillis();

        System.out.println("[Serial] 受信スレッド開始");

        while (port.isOpen()) {
            try {
                int numRead = port.readBytes(readBuffer, readBuffer.length);
                if (numRead > 0) {
                    for (int i = 0; i < numRead; i++) {
                        persistentBuffer.add(readBuffer[i]);
                    }
                    processBuffer(persistentBuffer);
                }
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat > 10_000) {
                    System.out.println("[Serial] ❤ alive | packets=" + packetDataList.size()
                        + " | buffer=" + persistentBuffer.size()
                        + " | mem=" + (Runtime.getRuntime().totalMemory()
                                    - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB"
                        + " | anchors=" + anchorPacketCount);
                    lastHeartbeat = now;
                }
            } catch (Exception e) {
                System.err.println("[Serial] 受信例外: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }
        System.out.println("[Serial] 受信スレッド終了（port.isOpen()=false）");
    }

    private void processBuffer(List<Byte> buffer) {
        while (buffer.size() >= TOTAL_PACKET_SIZE) {
            int head = buffer.get(0) & 0xFF;
            if ((head >= '0' && head <= '9') || (head >= 'A' && head <= 'F')) {
                if (buffer.size() >= TOTAL_PACKET_SIZE) {
                    List<Byte> packetBytes = new ArrayList<>(buffer.subList(0, TOTAL_PACKET_SIZE));
                    try {
                        parseAndStorePacket(packetBytes);
                    } catch (Exception e) {
                        System.err.println("Packet Parse Error: " + e.getMessage());
                    }
                    buffer.subList(0, TOTAL_PACKET_SIZE).clear();
                } else {
                    break; 
                }
            } else if ((buffer.get(0) & 0xFF) == '[') {
                int newlineIdx = -1;
                for (int i = 1; i < buffer.size(); i++) {
                    if (buffer.get(i) == '\n') { newlineIdx = i; break; }
                }
                if (newlineIdx == -1) break; 

                byte[] textBytes = new byte[newlineIdx + 1];
                for (int i = 0; i <= newlineIdx; i++) textBytes[i] = buffer.get(i);
                String fwMsg = new String(textBytes).trim();
                if (fwMsg.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
                    System.out.println("[FW] " + fwMsg);
                } else {
                    System.err.println("[FW:WARN] バイナリ誤検知をスキップ");
                }
                buffer.subList(0, newlineIdx + 1).clear();
            } else {
                buffer.remove(0);
            }
        }
    }

    private void parseAndStorePacket(List<Byte> packetBytes) throws Exception {
        byte[] data = new byte[packetBytes.size()];
        for(int i=0; i<packetBytes.size(); i++) data[i] = packetBytes.get(i);

        String headerString = new String(data, MAGIC_SIZE, HEADER_SIZE, "US-ASCII");

        if (!headerString.matches("[0-9A-Fa-f ]+")) {
            System.err.println("[WARN] 不正パケット検出（ヘッダーに非HEX文字）: スキップします");
            return; 
        }

        byte[] cir_hex = new byte[CIR_SIZE];
        System.arraycopy(data, MAGIC_SIZE + HEADER_SIZE, cir_hex, 0, CIR_SIZE);

        long reporter_addr = Long.parseLong(headerString.substring(0, 16), 16);
        long anchor_addr = Long.parseLong(headerString.substring(17, 33), 16);
        long tag_addr = Long.parseLong(headerString.substring(34, 50), 16);

        long TARGET_TAG_ID = 1677L;
        if (anchor_addr != TARGET_TAG_ID && tag_addr != TARGET_TAG_ID) {
            return;
        }
        long actual_anchor_addr = (anchor_addr != TARGET_TAG_ID) ? anchor_addr : tag_addr;

        double range_m = Long.parseLong(headerString.substring(51, 59), 16) / 1000.0;
        double fp_power_dbm = convertToSigned32Bit(Long.parseLong(headerString.substring(60, 68), 16)) / 1000.0;
        double rx_power_dbm = convertToSigned32Bit(Long.parseLong(headerString.substring(69, 77), 16)) / 1000.0;
        double rng_raw_m = Long.parseLong(headerString.substring(78, 86), 16) / 1000.0;
        int rxpacc = (int) (Long.parseLong(headerString.substring(87, 95), 16));

        long currentTimestamp = System.currentTimeMillis();

        String anchorKey = String.format("%d", actual_anchor_addr);
        anchorPacketCount.merge(anchorKey, 1, Integer::sum);

        if (packetDataList.size() > 50_000) { 
            System.err.println("[WARNING] packetDataListが上限到達。先頭10000件を削除します。");
            packetDataList.subList(0, 10_000).clear();
        }
        
        packetDataList.add(new PacketData(
            sessionId,
            packetDataList.size(),
            currentTimestamp,
            reporter_addr,anchor_addr,tag_addr,
            range_m,rng_raw_m,
            fp_power_dbm,rx_power_dbm,rxpacc,
            cir_hex
        ));

        apiBuffer.put(anchorKey, new double[]{range_m, fp_power_dbm, rx_power_dbm, (double)currentTimestamp});

        System.out.println("Anchor ID: " + String.format("%d", actual_anchor_addr) + " | CIR Len: " + cir_hex.length + " bytes");
    }

    private int convertToSigned32Bit(long value) {
        if ((value & 0x80000000L) != 0) {
            return (int) (value | 0xFFFFFFFF00000000L);
        } else {
            return (int) value;
        }
    }

    @FXML
    private void getTimeRange(ActionEvent event) {
        try {
            selectedTimeRange = Integer.parseInt(TimeRange.getText());
            System.out.println("時間：" + selectedTimeRange);
        } catch (NumberFormatException e) {
            showAlert("エラー", "無効な時間範囲が入力されました。");
        }
    }

    private void saveSessionMetadata() {
        String metadataPath = selectedDirectory.getAbsolutePath() + 
                             "/" + selectedDirectoryName + "_metadata.json";
        
        try (FileWriter writer = new FileWriter(metadataPath)) {
            writer.append("{\n");
            writer.append(String.format("  \"session_id\": \"%s\",\n", sessionId));
            writer.append(String.format("  \"timestamp_start_ms\": %d,\n", 
                            System.currentTimeMillis()));
            writer.append(String.format("  \"total_packets\": %d,\n", 
                            packetDataList.size()));
            writer.append("  \"environment\": \"[手動入力] 室内/LOS等の環境情報\",\n");
            writer.append("  \"anchor_positions\": {\n");
            writer.append("    \"comment\": \"anchorアドレス: [x, y, z] 単位:m\",\n");
            writer.append("    \"example\": {\n");
            writer.append("      \"AAAA\": [0.0, 0.0, 1.5]\n");
            writer.append("    }\n");
            writer.append("  },\n");
            writer.append("  \"rf_config\": {\n");
            writer.append("    \"channel\": 2,\n");
            writer.append("    \"prf\": \"64MHz\",\n");
            writer.append("    \"data_rate\": \"6.81Mbps\",\n");
            writer.append("    \"preamble_length\": 128\n");
            writer.append("  },\n");
            writer.append("  \"notes\": \"S1-4 role assignment with magic byte sync\"\n");
            writer.append("}\n");
            
            System.out.println("Session metadata saved to: " + metadataPath);
        } catch (IOException e) {
            System.err.println("Metadata save error: " + e.getMessage());
        }
    }

    @FXML
    private void handleCloseButtonClick(ActionEvent event) {
        // ★ 修正: 保存モードなら、Close時にも最低限のパスが存在するかチェック
        if (selectedFormat != FileFormat.NONE) {
            if (selectedDirectory == null || selectedDirectory.getAbsolutePath().trim().isEmpty() || 
                selectedDirectoryName == null || selectedDirectoryName.trim().isEmpty()) {
                showAlert("エラー", "保存先ディレクトリとファイル名を入力してください。");
                return;
            }
        }
        
        // タイマーの停止
        if (timer != null) timer.cancel();
        if (apiTimer != null) apiTimer.cancel(); 
        
        getFileName(null);

        // ポートのクローズ
        if (port != null && port.isOpen()) {
            port.closePort();
            System.out.println("Port closed.");
        }

        // ★ 修正: 保存モードの判定
        if (selectedFormat != FileFormat.NONE) {
            saveSessionMetadata();
            
            if (selectedFormat == FileFormat.CSV) {
                saveDataAsCSV(packetDataList);
            } else if (selectedFormat == FileFormat.JSON) {
                saveDataAsJSON(packetDataList);
            }
        } else {
            System.out.println("[Session] ローカル保存なしモードのため、ファイル出力はスキップされました。");
        }

        packetDataList.clear();
        anchorPacketCount.clear(); 
        System.gc(); 
        System.out.println("[Session] cleared. mem=" +
        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB");
    }

    private void saveDataAsCSV(List<PacketData> list) {
        String baseFilePath = selectedDirectory.getAbsolutePath() + "/" + selectedDirectoryName + ".csv";
        String filePath = baseFilePath;

        int counter = 1;
        File csvFile = new File(filePath);
        while (csvFile.exists()) {
            filePath = selectedDirectory.getAbsolutePath() + "/" + 
                    selectedDirectoryName + String.format("(%d)", counter) + ".csv";
            csvFile = new File(filePath);
            counter++;
        }
        System.out.println("CSV will be saved to: " + filePath);

        try (FileWriter writer = new FileWriter(filePath, true)) {
            writer.append(
                "session_id,packet_seq,timestamp_ms," +
                "reporter_addr,anchor_addr,tag_addr," +
                "range_m,range_raw_m,fp_power_dbm,rx_power_dbm,rxpacc,tof_seconds," +
                "cir_amplitude_hex,cir_phase_hex,cir_raw_hex\n"
            );

            for (PacketData p : list) {
                writer.append(String.format(
                    "%s,%d,%d,%d,%d,%d,%.6f,%.6f,%.4f,%.4f,%d,%.12e,",
                    p.sessionId, p.packetSeq, p.timestamp,
                    p.reporter_addr, p.anchor_addr, p.tag_addr,
                    p.range_m, p.range_raw_m, p.fp_power_dbm, p.rx_power_dbm, p.rxpacc,
                    p.tofSeconds
                ));

                writer.append("\"");
                for (int i = 0; i < p.cirAmplitude.length; i++) {
                    writer.append(String.format("%04X", (int)p.cirAmplitude[i]));
                    if (i < p.cirAmplitude.length - 1) writer.append(",");
                }
                writer.append("\",");
                
                writer.append("\"");
                for (int i = 0; i < p.cirPhase.length; i++) {
                    writer.append(String.format("%.6f", p.cirPhase[i])); 
                    if (i < p.cirPhase.length - 1) writer.append(",");
                }
                writer.append("\",");
                
                writer.append("\"");
                for (byte b : p.cirRawHex) {
                    writer.append(String.format("%02X", b));
                }
                writer.append("\"\n");
            }
            
            writer.flush();
            System.out.println("Saved CSV to: " + filePath + " (" + list.size() + " packets)");
        } catch (IOException e) {
            System.err.println("CSV Save Error: " + e.getMessage());
        }
    }

    private void saveDataAsJSON(List<PacketData> list) {
        String filePath = selectedDirectory.getAbsolutePath() + "/" + selectedDirectoryName + ".json";
        try (FileWriter writer = new FileWriter(filePath, true)) {
            writer.append("[\n");
            for (PacketData p : list) {
                writer.append("{");
                writer.append(String.format("\"session_id\": \"%s\",", p.sessionId));
                writer.append(String.format("\"packet_seq\": %d,", p.packetSeq));
                writer.append(String.format("\"timestamp_ms\": %d,", p.timestamp));
                writer.append(String.format("\"reporter_addr\": %d,", p.reporter_addr));
                writer.append(String.format("\"anchor_addr\": %d,", p.anchor_addr));
                writer.append(String.format("\"tag_addr\": %d,", p.tag_addr));
                writer.append(String.format("\"range_m\": %f,", p.range_m));
                writer.append(String.format("\"fp_power_dbm\": %f,", p.fp_power_dbm));
                writer.append(String.format("\"rx_power_dbm\": %f,", p.rx_power_dbm));
                writer.append(String.format("\"rxpacc\": %d,", p.rxpacc));
                writer.append("\"cir_hex\": \"");
                for (byte b : p.cirRawHex) {
                    writer.append(String.format("%02X", b));
                }
                writer.append("\"},\n");
            }
            writer.append("]\n");
            System.out.println("Saved JSON to: " + filePath);
        } catch (IOException e) {
            System.err.println("JSON Save Error: " + e.getMessage());
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static class PacketData {
        private static final double SPEED_OF_LIGHT = 299792458.0;

        String sessionId; 
        int packetSeq; 
        long timestamp; 
        
        long reporter_addr; 
        long anchor_addr; 
        long tag_addr; 
        
        double range_m; 
        double fp_power_dbm; 
        double rx_power_dbm; 
        double range_raw_m; 
        int rxpacc; 

        byte[] cirRawHex;
        double[] cirAmplitude;
        double[] cirPhase;
        double tofSeconds;

        PacketData(String sessionId, int packetSeq, long timeStamp,long reporterAddr, long anchorAddr, long tagAddr, 
               double rangeM, double rangeRawM, double fpPowerDbm, 
               double rxPowerDbm, int rxpacc, byte[] cirData) {
            this.sessionId = sessionId;
            this.packetSeq = packetSeq;
            this.timestamp = timeStamp; 
            this.reporter_addr = reporterAddr; this.anchor_addr = anchorAddr; this.tag_addr = tagAddr;
            this.range_m = rangeM; this.range_raw_m = rangeRawM; this.fp_power_dbm = fpPowerDbm; 
            this.rx_power_dbm = rxPowerDbm;  this.rxpacc = rxpacc;
            this.cirRawHex = cirData;

            decomposeCIRdata(cirData);
        }

        private void decomposeCIRdata(byte[] cirData) {
            cirAmplitude = new double[128];
            cirPhase = new double[128];
            
            for (int i = 0; i < 128; i++) {
                int offset = i * 4;
                short real = (short)((cirData[offset+1] << 8) | (cirData[offset] & 0xFF));
                short imag = (short)((cirData[offset+3] << 8) | (cirData[offset+2] & 0xFF));
                
                cirAmplitude[i] = Math.sqrt(real * real + imag * imag);
                cirPhase[i] = Math.atan2(imag, real);
            }
            tofSeconds = range_m / SPEED_OF_LIGHT; 
        }
    }

    private void sendBufferToApi() {
        if (apiBuffer.isEmpty()) return;

        long apiStart = System.currentTimeMillis(); 
        System.out.println("[API] 送信開始 buffer=" + apiBuffer.size() + "件");

        try {
            StringBuilder json = new StringBuilder();
            json.append("{\"measurements\": {");
            
            int count = 0;
            for (Map.Entry<String, double[]> entry : apiBuffer.entrySet()) {
                if (count > 0) json.append(", ");
                json.append("\"").append(entry.getKey()).append("\": {");
                json.append("\"rng\": ").append(entry.getValue()[0]).append(", ");
                json.append("\"rsl\": ").append(entry.getValue()[1]).append(", ");
                json.append("\"rng_raw\": ").append(entry.getValue()[2]).append(", ");
                json.append("\"timestamp\": ").append((long)entry.getValue()[3]);
                json.append("}");
                count++;
            }
            json.append("}}");

            apiBuffer.clear();

            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setConnectTimeout(1000); 
            conn.setReadTimeout(1000);    

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - apiStart; 
            if (responseCode != 200) {
                System.err.println("[API] エラー HTTP " + responseCode + " (" + elapsed + "ms)");
            } else {
                System.out.println("[API] 送信完了 HTTP " + responseCode + " (" + elapsed + "ms)");
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - apiStart; 
            System.err.println("[API] 通信例外 (" + elapsed + "ms): " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}