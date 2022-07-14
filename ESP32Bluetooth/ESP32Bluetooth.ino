// Estou usando um ESP32 nodemcu, mais pode ser qualquer ESP32 que tenha Bluetooth
// ou um Arduino com uma placa de Bluetooth tipo HC-05

#include "BluetoothSerial.h"

#define LED 2     // GPIO do Led interno do nodemcu. Mude para qualquer outra GPIO e coloque um LED
#define BUTTON 4  // GPIO para instalar um botão NA (normalmente aberto) tipo campainha

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
   #error Bluetooth não disponivel! 
#endif

BluetoothSerial SerialBT;
const char* dispNome = "ESP32test"; // Nome a ser exibido pelo Bluetooth 

// Classe Botao - Essa classe trata o efeito Bounce gerado por um botão 
class Botao {
  private:
    byte pinoBtn;
    boolean botaoEstado;                 // estado atual do botao
    boolean lastbotaoEstado = LOW;       // valor da ultima leitura do botao
    unsigned long ultimoTempoBounce = 0;  // tempo da ultima modificacao do estado do LED
    unsigned long bounceIntervalo = 50;    
    boolean primeiraVez = true;

  public:
    Botao(byte _pinoBtn) {
      this->pinoBtn = _pinoBtn;
      pinMode(pinoBtn, INPUT_PULLUP);
    }

    boolean Pressionado() {
       boolean ret = false;
       int reading = digitalRead(pinoBtn);
       if (reading==LOW) primeiraVez = false;
       if (reading != lastbotaoEstado) {
          ultimoTempoBounce = millis();
       }
       if ((millis() - ultimoTempoBounce) > bounceIntervalo) {
          if (reading != botaoEstado) {
             botaoEstado = reading;
             if (!primeiraVez && botaoEstado == HIGH) {
                ret = true;
             }
          }
      }
      lastbotaoEstado = reading;
      return ret;
    }
    
    void MudaBounceIntervalo(unsigned long valor){
       bounceIntervalo = valor;  
    }
};

Botao btn = Botao(BUTTON); // Instancia a classe Butao criando um objeto "btn"

void setup() {
   pinMode(LED,OUTPUT);
   pinMode(BUTTON,INPUT_PULLUP);
     
   Serial.begin(115200);
   while(!Serial); // Aguarde até a Serial ficar disponível
   Serial.println(F("Inicializado"));

   // Inicializa o Bluetooth
   if (SerialBT.begin(dispNome)){
      Serial.println(F("Bluetooth iniciado"));
   }else{
      Serial.println(F("Bluetooth não iniciado"));
      delay(5000);
      ESP.restart(); // Faz reset se não conseguir inicializar 
   }
}

void loop() {
   Serial.println("LED: "+String(digitalRead(LED)));
   btn.MudaBounceIntervalo(80); // Ajustando o valor do bounce, o valor defalut é 50.
   while(true)
   {
      if (btn.Pressionado()){ // Se o botão for pressionado
         digitalWrite(LED,!digitalRead(LED)); // Inverte o estado do LED
         SerialBT.println(digitalRead(LED)); // Envia o status do LED para o Bluetooth
         Serial.println("LED: "+String(digitalRead(LED)));
      }
      if (SerialBT.available()) { // Verifica se tem dados vindo do Bluetooth
         String expC = "";
         long timeOut = millis();
         while(SerialBT.available() && millis()-timeOut<100){
            int ch = SerialBT.read();
            if (ch==10) break;
            expC += (char)ch;
         }
         Serial.println(expC);
         if (expC.equals("*")){ // Se receber "*" muda o estado do LED
            digitalWrite(LED,!digitalRead(LED)); // Inverte o estado do LED
            SerialBT.println(digitalRead(LED)); // Envia o status do LED para o Bluetooth
            Serial.println("LED: "+String(digitalRead(LED)));
         }else if (expC.equals("ST")){ // Se receber "ST" apenas envia o status do LED
            SerialBT.println(digitalRead(LED)); // Envia o status do LED
         }
      }
   }
}
