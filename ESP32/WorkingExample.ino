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

#define BUTTON_IO13 13

// -------- Button Timing --------
unsigned long pressStartTime = 0;
unsigned long lastReleaseTime = 0;

bool buttonPressed = false;
bool waitingForDouble = false;

const unsigned long doublePressTime = 350;
const unsigned long longPressTime = 800;

bool lastState13 = HIGH;
bool continuousMode = false;

// -------- Marker Command --------
const char* pendingCommand = NULL;

// ---------- UDP ----------
WiFiUDP udp;
const int discoveryPort = 4210;

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
void startCamera() {
  

  camera_config_t config;

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

  config.pin_sscb_sda = SIOD_GPIO_NUM;
  config.pin_sscb_scl = SIOC_GPIO_NUM;

  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;

  config.xclk_freq_hz = 16000000;
  config.pixel_format = PIXFORMAT_JPEG;



  if(psramFound()){
    config.frame_size = FRAMESIZE_SXGA;
    config.jpeg_quality = 12;
    config.fb_count = 2;
  } else {
    config.frame_size = FRAMESIZE_VGA;
    config.jpeg_quality = 15;
    config.fb_count = 1;
  }

  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.grab_mode = CAMERA_GRAB_LATEST;

  if(esp_camera_init(&config) != ESP_OK){
    Serial.println("Camera init failed");
    esp_deep_sleep_start();
  }

  sensor_t * s = esp_camera_sensor_get();
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
}

// ---------- Stream Handler ----------
static esp_err_t stream_handler(httpd_req_t *req){

  httpd_resp_set_type(req, _STREAM_CT);
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  httpd_resp_set_hdr(req, "Cache-Control", "no-cache");

  while(true){

    // Inject command marker
    if(pendingCommand != NULL){

      httpd_resp_send_chunk(req, pendingCommand, strlen(pendingCommand));
      pendingCommand = NULL;
    }

    camera_fb_t * fb = esp_camera_fb_get();

    if(!fb){
      delay(30);
      continue;
    }

    size_t hlen = snprintf(part_buf, 64, _PART_HDR, fb->len);

    httpd_resp_send_chunk(req, _BOUNDARY, strlen(_BOUNDARY));
    httpd_resp_send_chunk(req, part_buf, hlen);
    httpd_resp_send_chunk(req, (const char*)fb->buf, fb->len);

    esp_camera_fb_return(fb);

    delay(30);
  }

  

  return ESP_OK;
}

void startCameraServer(){

  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;

  httpd_handle_t server = NULL;

  if(httpd_start(&server, &config) == ESP_OK){

    httpd_uri_t stream_uri;

    stream_uri.uri = "/stream";
    stream_uri.method = HTTP_GET;
    stream_uri.handler = stream_handler;
    stream_uri.user_ctx = NULL;

    httpd_register_uri_handler(server, &stream_uri);

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

    IPAddress ip = WiFi.localIP();

    char response[32];

    sprintf(response,"ESP32_CAM:%d.%d.%d.%d",
            ip[0],ip[1],ip[2],ip[3]);

    udp.beginPacket(udp.remoteIP(),udp.remotePort());
    udp.write((uint8_t*)response,strlen(response));
    udp.endPacket();
  }
}

// ---------- WiFi ----------
bool connectToWiFi(){

  int n = WiFi.scanNetworks();

  for(int i=0;i<networkCount;i++){

    for(int j=0;j<n;j++){

      if(WiFi.SSID(j) == networks[i].ssid){

        WiFi.begin(networks[i].ssid,networks[i].password);

        for(int k=0;k<15;k++){

          if(WiFi.status()==WL_CONNECTED) break;

          delay(500);
        }

        if(WiFi.status()==WL_CONNECTED){

          Serial.print("Connected to: ");
          Serial.println(WiFi.SSID());

          Serial.print("IP Address: ");
          Serial.println(WiFi.localIP());

          return true;
        }
      }
    }
  }

  return false;
}

// ---------- Buttons ----------
void handleButtons(){

  bool state = digitalRead(BUTTON_IO13);
  unsigned long now = millis();

  if(state == LOW && !buttonPressed){

    buttonPressed = true;
    pressStartTime = now;
  }

  if(state == HIGH && buttonPressed){

    buttonPressed = false;

    unsigned long pressDuration = now - pressStartTime;

    if(pressDuration >= longPressTime){

      Serial.println("STOP");
      pendingCommand = "BTN:STOP";
      waitingForDouble = false;
      return;
    }

    if(waitingForDouble && (now-lastReleaseTime)<doublePressTime){

      continuousMode = !continuousMode;

      if(continuousMode){

        Serial.println("CONTINUOUS ON");
        pendingCommand = "BTN:CONT";

      } else {

        Serial.println("CONTINUOUS OFF");
        pendingCommand = "BTN:STOP";
      }

      waitingForDouble=false;

    } else {

      waitingForDouble=true;
      lastReleaseTime=now;
    }
  }

  if(waitingForDouble && (now-lastReleaseTime)>doublePressTime){

    Serial.println("READ TEXT");
    pendingCommand="BTN:READ";
    waitingForDouble=false;
  }
}

// ---------- Setup ----------
void setup(){

  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG,0);

  Serial.begin(115200);

  pinMode(FLASH_LED_PIN,OUTPUT);
  pinMode(BUTTON_IO13,INPUT_PULLUP);

  startCamera();

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);

  if(!connectToWiFi()){

    WiFi.softAP("ESP32-CAM","REDACTED");
  }

  setupPowerManagement();

  if(WiFi.status()==WL_CONNECTED){

    if(MDNS.begin("esp32cam")){
      Serial.println("mDNS started");
    }
  }

  udp.begin(discoveryPort);

  startCameraServer();

  Serial.println("System Ready");
}

// ---------- Loop ----------
void loop(){

  handleDiscovery();

  handleButtons();

  delay(1);
}