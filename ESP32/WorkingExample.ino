#include "esp_camera.h"
#include <WiFi.h>
#include <WebSocketsServer.h>
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"
#include <ESPmDNS.h>
#include <WiFiUdp.h>
#include "esp_http_server.h"
#include "esp_pm.h"
#include "esp_wifi.h"
#include "secrets.h"
#include "GestureDetector.h"

#ifndef AP_SSID
#define AP_SSID "ESP32-CAM-READER"
#endif
#ifndef AP_PASSWORD
#define AP_PASSWORD "replace-me"
#endif

// WPA2 needs 8..63 characters. WiFi.softAP() rejects anything else and the fallback
// network would silently never appear, so fail the build instead.
static_assert(sizeof(AP_PASSWORD) - 1 >= 8 && sizeof(AP_PASSWORD) - 1 <= 63,
              "AP_PASSWORD in secrets.h must be 8 to 63 characters");

#define BUTTON_IO13 13

// -------- Button gestures (logic and timing live in GestureDetector.h, host-tested) --------
GestureDetector gestures;
bool continuousMode = false;

// -------- Reliability limits --------
static const int kMaxConsecutiveCameraFailures = 100;     // ~3 s without a frame
static const unsigned long kWifiLostRestartMs = 30000UL;  // reboot if the router stays gone
static const unsigned long kWifiConnectTimeoutMs = 7500UL; // per candidate network
bool stationMode = false;
bool wifiLost = false;
unsigned long wifiLostSinceMs = 0;

// ---------- UDP ----------
WiFiUDP udp;
const int discoveryPort = 4210;
WebSocketsServer eventServer(81);

// ---------- Flash ----------
#define FLASH_LED_PIN 4

// ---------- Camera Pins ----------
#define PWDN_GPIO_NUM   32
#define RESET_GPIO_NUM  -1
#define XCLK_GPIO_NUM    0
#define SIOD_GPIO_NUM   26
#define SIOC_GPIO_NUM   27
#define Y9_GPIO_NUM     35
#define Y8_GPIO_NUM     34
#define Y7_GPIO_NUM     39
#define Y6_GPIO_NUM     36
#define Y5_GPIO_NUM     21
#define Y4_GPIO_NUM     19
#define Y3_GPIO_NUM     18
#define Y2_GPIO_NUM      5
#define VSYNC_GPIO_NUM  25
#define HREF_GPIO_NUM   23
#define PCLK_GPIO_NUM   22

// ---------- Stream ----------
static const char* _STREAM_CT = "multipart/x-mixed-replace;boundary=frame";
static const char* _BOUNDARY  = "\r\n--frame\r\n";
static const char* _PART_HDR  = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static char part_buf[64];

// ---------- Power ----------
void setupPowerManagement() {
  esp_pm_config_esp32_t pm_config = {
    .max_freq_mhz = 160,
    .min_freq_mhz = 80,
    .light_sleep_enable = false
  };
  esp_pm_configure(&pm_config);
  esp_wifi_set_max_tx_power(68);
}

// ---------- Camera ----------
// Returns false (instead of halting) so setup() can retry and then reboot.
bool startCamera() {

  camera_config_t config = {};

  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;

  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;

  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;

  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;

  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;

  config.xclk_freq_hz = 16000000;
  config.pixel_format = PIXFORMAT_JPEG;



  if(psramFound()){
    config.frame_size = FRAMESIZE_SXGA;
    config.jpeg_quality = 12;
    config.fb_count = 2;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.grab_mode = CAMERA_GRAB_LATEST;
  } else {
    config.frame_size = FRAMESIZE_VGA;
    config.jpeg_quality = 15;
    config.fb_count = 1;
    config.fb_location = CAMERA_FB_IN_DRAM;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
  }

  const esp_err_t initResult = esp_camera_init(&config);
  if(initResult != ESP_OK){
    Serial.printf("Camera init failed: 0x%x\n", (unsigned)initResult);
    return false;
  }

  sensor_t * s = esp_camera_sensor_get();
  if(s == NULL){
    Serial.println("Camera sensor handle missing");
    return false;
  }
  s->set_brightness(s, 2);     // -2 to 2
  s->set_contrast(s, -2);       // -2 to 2
  s->set_saturation(s, 0);     // -2 to 2
  s->set_special_effect(s, 0); // 0 to 6 (0 - No Effect, 1 - Negative, 2 - Grayscale, 3 - Red Tint, 4 - Green Tint, 5 - Blue Tint, 6 - Sepia)
  s->set_whitebal(s, 1);       // 0 = disable , 1 = enable
  s->set_awb_gain(s, 1);       // 0 = disable , 1 = enable
  s->set_wb_mode(s, 0);        // 0 to 4 - if awb_gain enabled (0 - Auto, 1 - Sunny, 2 - Cloudy, 3 - Office, 4 - Home)
  s->set_exposure_ctrl(s, 1);  // 0 = disable , 1 = enable
  s->set_aec2(s, 0);           // 0 = disable , 1 = enable
  s->set_ae_level(s, 0);       // -2 to 2
  s->set_aec_value(s, 300);    // 0 to 1200
  s->set_gain_ctrl(s, 1);      // 0 = disable , 1 = enable
  s->set_agc_gain(s, 0);       // 0 to 30
  s->set_gainceiling(s, (gainceiling_t)0);  // 0 to 6
  s->set_bpc(s, 0);            // 0 = disable , 1 = enable
  s->set_wpc(s, 1);            // 0 = disable , 1 = enable
  s->set_raw_gma(s, 1);        // 0 = disable , 1 = enable
  s->set_lenc(s, 1);           // 0 = disable , 1 = enable
  s->set_hmirror(s, 0);        // 0 = disable , 1 = enable
  s->set_vflip(s, 0);          // 0 = disable , 1 = enable
  s->set_dcw(s, 1);            // 0 = disable , 1 = enable
  s->set_colorbar(s, 0);       // 0 = disable , 1 = enable

  Serial.println("Camera ready");
  return true;
}

// There is no screen or speaker on the glasses, so a camera fault is signalled by
// blinking the flash LED (visible to a helper), then rebooting to try again.
static void signalCameraFailureAndRestart(){
  Serial.println("Camera unavailable - restarting");
  for(int i=0;i<5;i++){
    digitalWrite(FLASH_LED_PIN,HIGH);
    delay(80);
    digitalWrite(FLASH_LED_PIN,LOW);
    delay(120);
  }
  delay(2000);
  ESP.restart();
}

// ---------- Stream Handler ----------
static esp_err_t stream_handler(httpd_req_t *req){

  httpd_resp_set_type(req, _STREAM_CT);
  httpd_resp_set_hdr(req, "Cache-Control", "no-cache");

  int cameraFailures = 0;

  while(true){
    camera_fb_t * fb = esp_camera_fb_get();

    if(!fb){
      // The server has a single worker task: spinning here forever would also block
      // /health and every reconnect. A camera that stays silent needs a reboot.
      if(++cameraFailures >= kMaxConsecutiveCameraFailures){
        Serial.println("Camera stopped delivering frames - restarting");
        delay(100);
        ESP.restart();
      }
      delay(30);
      continue;
    }
    cameraFailures = 0;

    size_t hlen = snprintf(part_buf, 64, _PART_HDR, fb->len);

    esp_err_t result = httpd_resp_send_chunk(req, _BOUNDARY, strlen(_BOUNDARY));
    if(result == ESP_OK){
      result = httpd_resp_send_chunk(req, part_buf, hlen);
    }
    if(result == ESP_OK){
      result = httpd_resp_send_chunk(req, (const char*)fb->buf, fb->len);
    }

    esp_camera_fb_return(fb);

    if(result != ESP_OK){
      return result;
    }

    delay(30);
  }

  return ESP_OK;
}

static esp_err_t health_handler(httpd_req_t *req){
  const bool connected = WiFi.status() == WL_CONNECTED;
  const String ip = (connected ? WiFi.localIP() : WiFi.softAPIP()).toString();
  char response[192];
  snprintf(response, sizeof(response),
           "{\"name\":\"smart-assistive-reader\",\"version\":\"1.0.0\",\"stream\":\"/stream\",\"capture\":\"/capture\",\"events\":\"ws://%s:81\",\"wifi\":\"%s\",\"ip\":\"%s\"}",
           ip.c_str(), connected ? "sta" : "ap", ip.c_str());
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, response, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t capture_handler(httpd_req_t *req){
  camera_fb_t *fb = esp_camera_fb_get();
  if(!fb){
    return httpd_resp_send_500(req);
  }

  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  esp_err_t result = httpd_resp_send(req, (const char*)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return result;
}

void startCameraServer(){

  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;
  // One worker task serves every request. Without timeouts a client that stalls
  // mid-transfer holds it forever; with LRU purge a dead socket is evicted when a
  // new client needs a slot, so reconnecting always works.
  config.recv_wait_timeout = 5;
  config.send_wait_timeout = 5;
  config.lru_purge_enable = true;

  httpd_handle_t server = NULL;

  if(httpd_start(&server, &config) == ESP_OK){

    // Zero-initialise: newer ESP-IDF versions add fields (is_websocket, ...) that are
    // read by the server and must not be left as stack garbage.
    httpd_uri_t stream_uri = {};

    stream_uri.uri = "/stream";
    stream_uri.method = HTTP_GET;
    stream_uri.handler = stream_handler;
    stream_uri.user_ctx = NULL;

    httpd_register_uri_handler(server, &stream_uri);

    httpd_uri_t health_uri = {
      .uri = "/health",
      .method = HTTP_GET,
      .handler = health_handler,
      .user_ctx = NULL
    };
    httpd_register_uri_handler(server, &health_uri);

    httpd_uri_t capture_uri = {
      .uri = "/capture",
      .method = HTTP_GET,
      .handler = capture_handler,
      .user_ctx = NULL
    };
    httpd_register_uri_handler(server, &capture_uri);

    Serial.println("Stream ready");
  }
}

// ---------- UDP Discovery ----------
char incoming[64];

void handleDiscovery(){

  int packetSize = udp.parsePacket();

  if(!packetSize) return;

  int len = udp.read(incoming, 63);

  if(len <= 0) return;

  incoming[len] = 0;

  if(strcmp(incoming,"WHO_IS_ESP32_CAM")==0){

    IPAddress ip = WiFi.status() == WL_CONNECTED ? WiFi.localIP() : WiFi.softAPIP();

    // An interface that has no address yet (router just dropped, AP not up) reads
    // 0.0.0.0. Advertising it would send the phone to a dead address; stay silent so
    // it falls through to its other candidates instead.
    if(ip[0]==0 && ip[1]==0 && ip[2]==0 && ip[3]==0) return;

    char response[32];

    snprintf(response,sizeof(response),"ESP32_CAM:%d.%d.%d.%d",
             ip[0],ip[1],ip[2],ip[3]);

    udp.beginPacket(udp.remoteIP(),udp.remotePort());
    udp.write((uint8_t*)response,strlen(response));
    udp.endPacket();
  }
}

// ---------- WiFi ----------
bool connectToWiFi(){

  int n = WiFi.scanNetworks();
  bool connected = false;

  for(int i=0;i<networkCount && !connected;i++){

    for(int j=0;j<n;j++){

      if(WiFi.SSID(j) == networks[i].ssid){

        WiFi.begin(networks[i].ssid,networks[i].password);

        const unsigned long startedAt = millis();
        while(WiFi.status()!=WL_CONNECTED && millis()-startedAt < kWifiConnectTimeoutMs){
          delay(100);
        }

        if(WiFi.status()==WL_CONNECTED){

          Serial.print("Connected to: ");
          Serial.println(WiFi.SSID());

          Serial.print("IP Address: ");
          Serial.println(WiFi.localIP());

          connected = true;
        } else {
          WiFi.disconnect();  // give up on this network before trying the next one
        }

        break;  // one attempt per configured network, even if it is listed twice
      }
    }
  }

  WiFi.scanDelete();  // scan results were never released before
  return connected;
}

// If the router disappears for good, reboot: setup() then re-scans and, if the router
// is still gone, falls back to the access point. Before this the glasses stayed
// unreachable until someone power-cycled them.
void watchWifi(){

  if(!stationMode) return;

  const unsigned long now = millis();

  if(WiFi.status()==WL_CONNECTED){
    wifiLost = false;
    return;
  }

  if(!wifiLost){
    wifiLost = true;
    wifiLostSinceMs = now;
  } else if(now - wifiLostSinceMs >= kWifiLostRestartMs){
    Serial.println("Wi-Fi lost - restarting");
    ESP.restart();
  }
}

// ---------- Buttons ----------
// Event names are the wire protocol (docs/protocol.md): READ, CONT, STOP over WebSocket :81.
// Debouncing, click/double-click/long-press timing and millis() wrap-around are handled
// by GestureDetector, which is unit-tested on a normal computer (tests/firmware).
void handleButtons(){

  const Gesture gesture = gestures.update(digitalRead(BUTTON_IO13) == LOW, millis());

  switch(gesture){

    case Gesture::Read:
      Serial.println("READ TEXT");
      eventServer.broadcastTXT("READ");
      break;

    case Gesture::ToggleContinuous:
      continuousMode = !continuousMode;
      if(continuousMode){
        Serial.println("CONTINUOUS ON");
        eventServer.broadcastTXT("CONT");
      } else {
        Serial.println("CONTINUOUS OFF");
        eventServer.broadcastTXT("STOP");
      }
      break;

    case Gesture::Stop:
      // Reset the local flag as well: previously a long press sent STOP but left this
      // true, so the next double-click "turned continuous mode off" and the user had
      // to double-click twice to start it again.
      continuousMode = false;
      Serial.println("STOP");
      eventServer.broadcastTXT("STOP");
      break;

    case Gesture::None:
      break;
  }
}

// ---------- Setup ----------
void setup(){

  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG,0);

  Serial.begin(115200);

  pinMode(FLASH_LED_PIN,OUTPUT);
  pinMode(BUTTON_IO13,INPUT_PULLUP);

  // Retry, then reboot: a transient fault (brown-out, loose ribbon cable) used to put the
  // board into deep sleep with no wake source, bricking it until a manual power cycle.
  bool cameraOk = false;
  for(int attempt=1; attempt<=3 && !cameraOk; attempt++){
    cameraOk = startCamera();
    if(!cameraOk){
      esp_camera_deinit();
      delay(500);
    }
  }
  if(!cameraOk) signalCameraFailureAndRestart();

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);

  stationMode = connectToWiFi();

  if(!stationMode){

    if(strcmp(AP_PASSWORD,"replace-me")==0 || strcmp(AP_PASSWORD,"CHANGE_ME_8_CHARS_MIN")==0){
      Serial.println("WARNING: AP_PASSWORD is a published placeholder - anyone can join the "
                     "fallback network and view the camera. Set your own in secrets.h.");
    }
    if(!WiFi.softAP(AP_SSID, AP_PASSWORD)){
      Serial.println("Failed to start fallback access point");
    }
  }

  setupPowerManagement();

  if(WiFi.status()==WL_CONNECTED){

    if(MDNS.begin("esp32cam")){
      Serial.println("mDNS started");
    }
  }

  udp.begin(discoveryPort);

  startCameraServer();
  eventServer.begin();

  Serial.println("System Ready");
}

// ---------- Loop ----------
void loop(){

  handleDiscovery();

  handleButtons();
  eventServer.loop();
  watchWifi();

  delay(1);
}