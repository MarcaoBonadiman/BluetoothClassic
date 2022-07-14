package br.com.marcaobonadiman.bluetoothclassic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        var m_myUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        lateinit var m_Adapter: BluetoothAdapter
        private var stopWorker = false
        private val _timer = Timer()
        private const val deviceNome = "ESP32test" // Nome do dispositivo que tem de parear (É o Bluetooth do ESP32 que está gerando)
        private var isConnectBT = false
        private var conectar = false
        var myThread: Thread? = null
        var deviceESP32: BluetoothDevice? = null
        private var m_bluetoothSocket: BluetoothSocket? = null
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Este LinearLayout contém o Botão da lâmpada e um TextView informativo. Ele só será exibido se conectar ao bluetooth
        LinearLayoutLed.visibility= View.GONE

        // Botão (Imagem da lâmpada)
        imageButtonLed.setOnClickListener{
            sendCommand("*\n") // Ao enviar um "*", faz o ESP32 inverter o estado do LED
        }

        if (ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.ACCESS_FINE_LOCATION) !== PackageManager.PERMISSION_GRANTED) {
            // Solicita permissão ao usuário
            if (ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity,Manifest.permission.ACCESS_FINE_LOCATION)) {
                ActivityCompat.requestPermissions(this@MainActivity,arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            } else {
                ActivityCompat.requestPermissions(this@MainActivity,arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }else{
            // Se já foi dado a permissão
            Init()
        }
    }

    // Resposta da solicitação ao usuário quanto a permissão
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if ((ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) === PackageManager.PERMISSION_GRANTED)) {
                        //Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show()
                        Init() // Se foi dado permissão inicia o app
                    }
                } else {
                    //Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
                    finish() // Se a permição foi negada, sai do app
                }
                return
            }
        }
    }

   private fun Init(){
       val bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
       m_Adapter = bluetoothManager.getAdapter()

       // Verifica se o Bluetooth está ligado, se não estiver pede ao usuário confirmação para ligar
       if (!m_Adapter.isEnabled){
           var intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
           resultLigaBT.launch(intent)
       }else{
           Init2()
       }
   }

    // Verifica a resposta da confirmação de ligar o Bluetooth
    @SuppressLint("MissingPermission")
    var resultLigaBT = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode==Activity.RESULT_OK){
            Init2()
        }else if (result.resultCode==Activity.RESULT_CANCELED){
            finish()
        }
    }

    // Inicializa a thread e procura pelo dispositivo "ESP32test"
    private fun Init2(){
        Servico() // Inicializa a thread que vai conectar e receber os dados do ESP32, se o dispositivo estiver pareado com sucesso.
        getPairedDevices() // Procura pelo dispositivo
    }

    // Função que envia dados ao ESP32
    private fun sendCommand(input: String) {
        if (m_bluetoothSocket!!.isConnected) {
            try{
                m_bluetoothSocket!!.outputStream.write(input.toByteArray())
            } catch(e: IOException) {
                e.printStackTrace()
            }
        }
    }

    // Função chamada ao Sair do App
    fun Sair(){
        try {
            stopWorker = true
            if (m_bluetoothSocket?.isConnected == true){
                m_bluetoothSocket!!.close()
                m_bluetoothSocket = null
            }
            finish()
        }catch ( e: IOException ){
            Log.e("Sair",e.message.toString())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Sair()
    }

    // Se o KeyBack foi pressionado para sair do App
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_BACK) {
            Sair()
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    // Procura pelo "ESP32test" e se está pareados. Se não achou sai do App, se achou e não está pareado, vai tentar Parear
    @SuppressLint("MissingPermission")
    private fun getPairedDevices() {
        val pairedDevice: Set<BluetoothDevice> = m_Adapter.getBondedDevices()
        if (pairedDevice.isNotEmpty()) {
            var flagErro = true
            for (device in pairedDevice) {
                //Log.e("Nome", device.name)
                if (device.name == deviceNome) {
                    deviceESP32 = device
                    conectar = true
                    flagErro = false
                    break
                }
            }
            if (flagErro) {  // Se não apareceu na lista dos pareados acima, tenta fazer o pareamnto
                Parear()
            }
        }
    }

    // Faz o pareamento com o dispositivo
    @SuppressLint("MissingPermission")
    private fun Parear() {
        try {
            val intentFilter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            this@MainActivity.registerReceiver(myReceiver, intentFilter)
            m_Adapter.startDiscovery()
        } catch (e: java.lang.Exception) {
            Log.e("Erro", e.message!!)
        }
    }

    // Faz o tratamento do pareamento
    private val myReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        var cnt:Int=0
        var achou:Boolean = false
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val msg = Message.obtain()
            val action = intent.action
            //Log.e("Scan", " -> $msg  ->  "+action.toString()  )
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                runOnUiThread {
                    TextViewStatus.text="Procurando pelo dispositivo:\n\n $deviceNome\n\naguarde..."
                }
            }else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                // Resposta da confirmação do Pareamento.
                //Log.e("Teste",BluetoothDevice.BOND_BONDED.toString()+"  "+BluetoothDevice.BOND_BONDING.toString()+"  "+BluetoothDevice.BOND_NONE.toString() )
                val state = intent.extras?.get(BluetoothDevice.EXTRA_BOND_STATE) as Int
                if(state==BluetoothDevice.BOND_NONE){ // Se o usuário cancelou
                    m_Adapter.cancelDiscovery()
                    finish()
                }else if(state==BluetoothDevice.BOND_BONDED){ // Se o usuário confirmou
                    m_Adapter.cancelDiscovery()
                    runOnUiThread {
                        TextViewStatus.text="Pareando com dispositivo:\n\n$deviceNome\n\naguarde..."
                    }
                    // Chama novamente a rotina "getPairedDevices" para tentar conectar após 5 segundos (Se houver erro, aumente esse tempo)
                    _timer.schedule(object : TimerTask() {
                        override fun run() {
                            getPairedDevices()
                        }
                    }, 5000)
                }
            }else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                var nomeDevice: String? = device?.name
                if (nomeDevice == deviceNome) {
                    m_Adapter.cancelDiscovery()
                    achou=true
                    runOnUiThread {
                        TextViewStatus.text="Achou o dispositivo:\n\n$deviceNome"
                    }
                    _timer.schedule(object : TimerTask() {
                        override fun run() {
                            // Vai fazer o pedido de pareamento, é usado um retardo de 100 ms para o aviso acima poder ser visalizado na tela.
                            // Esse pedido é assincrono, isto é, não espera pela resposta, por isso o "intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)" é
                            // usado na função de Parear, para captar a resposta de confirmação do pareamento.
                            var isBonded = createBond(device) // Vai solicitar permissão ao usuário para parear
                        }
                    }, 100)
                }
            }else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                // Finaliza o App pois não achou o dispositivo para parear.
                m_Adapter.cancelDiscovery()
                if (!achou){
                    //Log.e("Fim Scan", "BluetoothDevice ... Count:" + cnt);
                    runOnUiThread {
                        TextViewStatus.setTextColor(ContextCompat.getColor(context, R.color.red))
                        TextViewStatus.text="Dispositivo não encotrado,\n\n o App será finalizado"
                    }
                    _timer.schedule(object : TimerTask() {
                        override fun run() {
                            finish()
                        }
                    }, 4000)
                }
            }
        }
    }

    // Exibe na tela a solicitação do pareamento com o dispositivo (ESP32test)
    @Throws(java.lang.Exception::class)
    fun createBond(btDevice: BluetoothDevice?): Boolean {
        val class1 = Class.forName("android.bluetooth.BluetoothDevice")
        val createBondMethod = class1.getMethod("createBond")
        val returnValue = createBondMethod.invoke(btDevice) as Boolean
        return returnValue ?: false
    }

    // Thread de serviço. Se o dispositivo estiver pareado, tenta fazer a conexção. Se obtiver sucesso fica escutando os dados que o dispositivo está enviando
    fun Servico() {
        stopWorker = false
        myThread = object : Thread("My") {
            override fun run() {
                var expC = ""
                var input: InputStream
                var out: OutputStream
                while (!stopWorker) {
                    try {
                        if (conectar) {
                            conectar = false
                            Conectar() // Tenta conectar no ESP32
                        }
                        if (isConnectBT) { // Escuta se o dispoditivo está enviando dados ...
                            input = m_bluetoothSocket?.inputStream!!
                            out   = m_bluetoothSocket?.outputStream!!
                            var bytesAvailable: Int = input.available()
                            if (bytesAvailable > 0) {
                                while (input.available() > 0) {
                                    val packetBytes = ByteArray(bytesAvailable)
                                    input.read(packetBytes)
                                    expC += String(packetBytes)
                                    if (expC.contains("\n") || expC.length > 20) {
                                        expC = expC.replace("\n","").replace("\r","")
                                        //Log.e("Recebido: ", expC+" - "+ expC.length)
                                        var toSend = "Ack\n"
                                        if (expC.length==1) {
                                            ShowLampada(expC)
                                        }else{
                                            toSend = "Err\n"
                                        }
                                        out.write(toSend.toByteArray())
                                        out.flush()
                                        expC = ""
                                    }
                                }
                            }
                        }
                    } catch (e: java.lang.Exception) {
                        if (e.message!=null) Log.e("Loop", " Erro: " + e.message)
                    }
                    SystemClock.sleep(10) // Diminui o uso de CPU
                }
            }
        }
        (myThread as Thread).start()
    }

    // Exibe o status da lâmpada
    fun ShowLampada(expC :String){
        runOnUiThread {
            if (expC.equals("1")){
                imageButtonLed.setImageResource(R.drawable.lampadaacesa48)
            }else{
                imageButtonLed.setImageResource(R.drawable.lampadaapagada48)
            }
        }
    }

    // Conectar no dispositivo bluetooth do ESP32
    @SuppressLint("MissingPermission")
    private fun Conectar() {
        runOnUiThread {
            TextViewStatus.text="Conectando no dispostivo:\n\n $deviceNome\n\naguarde..."
        }
        try {
            m_bluetoothSocket = deviceESP32!!.createRfcommSocketToServiceRecord(m_myUUID)
            m_bluetoothSocket!!.connect()
            if (m_bluetoothSocket!!.isConnected()) {
                isConnectBT = true
                runOnUiThread {
                    TextViewStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
                    TextViewStatus.text="O dispositivo:\n\n $deviceNome\n\nestá conectado"
                    LinearLayoutLed.visibility= View.VISIBLE
                }
                sendCommand("ST\n") // Envia uma solicitação de status ao dispositivo
            } else {
                runOnUiThread {
                    TextViewStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
                    TextViewStatus.text="O dispositivo:\n\n $deviceNome\n\nestá pareado,\n\n mais não conseguiu conectar!"
                }
            }
        } catch (e: IOException) {
            runOnUiThread {
                TextViewStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
                TextViewStatus.text="O dispositivo:\n\n $deviceNome\n\nestá pareado,\n\n mais não conseguiu conectar!"
            }
        }
    }
}
