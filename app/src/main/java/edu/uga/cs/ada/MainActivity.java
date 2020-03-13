package edu.uga.cs.ada;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.net.wifi.WifiManager;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import org.tensorflow.lite.Interpreter;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public
class MainActivity extends AppCompatActivity {

    private static final String MODEL_PATH = "mobilenet_quant_v1_224.tflite";
    private static final String LABEL_PATH = "labels.txt";
    private static final int INPUT_SIZE = 224;

    private final int PICK_IMAGE_REQUEST = 71;
    private final int SEND_IMAGE_FOR_PROCESSING = 1000;
    TextView  connectionStatus;
    Button btnOnOff, btnDiscover, btnSend, btnProcessImage, btnDisplay, btnUpload;
    ImageView displayImage;
    //EditText writeMsg; TextView read_msg_box;
    ListView listView;

    WifiManager wifiManager;
    WifiP2pManager mManager;
    WifiP2pManager.Channel mChannel;
    BroadcastReceiver mReceiver;
    IntentFilter mIntentFilter;

    List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    String[] deviceNameArray; // we'll use this array to show device name in ListView
    WifiP2pDevice[] deviceArray; // we'll use this array to connect a device

    static final int MESSAGE_READ = 1;

    ServerClass serverClass;
    ClientClass clientClass;
    SendReceive sendReceive;

    boolean isHost;
    boolean isClient;

    Uri filePath;
    Bitmap bitmap;
    Bitmap decodedByte;
    String fileName;
    String finalImageResult;

    private Classifier classifier;
    private Executor executor = Executors.newSingleThreadExecutor();

    @Override
    protected
    void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initialWork();
        exqListener();
        initTensorFlowAndLoadModel();

    }

    //@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@read_msg_box@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
    //*****************//*****************//*****************//*****************
    Handler handler = new Handler(new Handler.Callback() {
        @Override
        public
        boolean handleMessage(Message msg) {
            switch (msg.what){
                case  MESSAGE_READ:
                    if(isClient){
                        byte[] readBuff = (byte[]) msg.obj;
                        String tempMsg = new String(readBuff, 0, msg.arg1);
                        Toast.makeText(getApplicationContext(),Integer.toString(readBuff.length), Toast.LENGTH_SHORT).show();
                        Toast.makeText(getApplicationContext(),"Image is Recived", Toast.LENGTH_SHORT).show();

                        byte[] decodedString = Base64.decode(tempMsg, Base64.DEFAULT);
                        decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                        saveImage(decodedByte);
                        displayImage.setImageBitmap(decodedByte);

                        break;
                    }else if( isHost){
                        byte[] readBuff = (byte[]) msg.obj;
                        String tempMsg = new String(readBuff, 0, msg.arg1);
                        finalImageResult = tempMsg;
                        Log.i("btnDisplay", "-----btnDisplay-----");
                        break;
                    }
            }
            return true;
        }
    });

    WifiP2pManager.PeerListListener peerListListener = new WifiP2pManager.PeerListListener() {
        @Override
        public
        void onPeersAvailable(WifiP2pDeviceList peerList) {

            if(!peerList.getDeviceList().equals(peers)){
                peers.clear();
                peers.addAll(peerList.getDeviceList());

                deviceNameArray = new String[peerList.getDeviceList().size()];
                deviceArray = new WifiP2pDevice[peerList.getDeviceList().size()];
                int index = 0;
                for(WifiP2pDevice device : peerList.getDeviceList()){
                    deviceNameArray[index] = device.deviceName;
                    deviceArray[index] = device;
                    index++;
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(getApplicationContext(),android.R.layout.simple_list_item_1,deviceNameArray);
                listView.setAdapter(adapter);
            }
            if(peers.size()==0){
                Toast.makeText(getApplicationContext(),"No devices found", Toast.LENGTH_SHORT).show();
                return;
            }
        }
    };

    WifiP2pManager.ConnectionInfoListener connectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {
        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
            final InetAddress groupOwnerAddress = wifiP2pInfo.groupOwnerAddress;

            if(wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner){
                connectionStatus.setText("Host");
                isHost = true;
                isClient = false;
                btnProcessImage.setVisibility(View.INVISIBLE);
                btnUpload.setVisibility(View.VISIBLE);
                btnDisplay.setVisibility(View.VISIBLE);
                btnSend.setVisibility(View.VISIBLE);

                serverClass = new ServerClass();
                serverClass.start();
            }else if(wifiP2pInfo.groupFormed){
                connectionStatus.setText("Client");
                isHost = false;
                isClient = true;
                btnUpload.setVisibility(View.INVISIBLE);
                btnDisplay.setVisibility(View.INVISIBLE);
                btnProcessImage.setVisibility(View.VISIBLE);
                btnSend.setVisibility(View.VISIBLE);

                clientClass = new ClientClass(groupOwnerAddress);
                clientClass.start();
            }

        }
    };


    @Override
    protected
    void onResume() {
        super.onResume();
        registerReceiver(mReceiver,mIntentFilter);
    }

    @Override
    protected
    void onPause() {
        super.onPause();
        unregisterReceiver(mReceiver);
    }

    private
    void exqListener() {
        btnUpload.setVisibility(View.INVISIBLE);
        btnDisplay.setVisibility(View.INVISIBLE);
        btnProcessImage.setVisibility(View.INVISIBLE);
        btnSend.setVisibility(View.INVISIBLE);

        btnOnOff.setOnClickListener(new ButtonOnOffListener());
        btnDiscover.setOnClickListener(new ButtonDiscoverListener());
        listView.setOnItemClickListener(new ListViewItemClicked());
        btnSend.setOnClickListener(new BottonSendListener());
        btnProcessImage.setOnClickListener(new ProcessImageListener()); // send Image to another app
        btnDisplay.setOnClickListener(new DisplayResultListener()); // call another activity to display results
        btnUpload.setOnClickListener(new UploadImageListener());
    }

    public class ButtonDiscoverListener implements View.OnClickListener{

        @Override
        public
        void onClick(View v) {
            mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {

                @Override
                public
                void onSuccess() {
                    // Discover started successfully
                    connectionStatus.setText("Discovey Started");
                }

                @Override
                public
                void onFailure(int reason) {
                    // Discover not started
                    connectionStatus.setText("Discovey Starting");

                }
            });

        }
    }
    public class ButtonOnOffListener implements View.OnClickListener{

        @Override
        public
        void onClick(View v) {

                // system < marshmallow,
                enableWifi();
        }
    }

    private
    void enableWifi() {
        if(wifiManager.isWifiEnabled()){
            wifiManager.setWifiEnabled(false);
            btnOnOff.setText("Wifi is OFF");
        }else {
            wifiManager.setWifiEnabled(true);
            btnOnOff.setText("Wifi is ON");
            }
    }

    private
    void initialWork() {
        btnOnOff = (Button) findViewById(R.id.onOff);
        btnDiscover = (Button) findViewById(R.id.discover);
        btnSend = (Button) findViewById(R.id.sendButton);
        listView = (ListView) findViewById(R.id.peerListView);
        //read_msg_box = (TextView) findViewById(R.id.readMsg);
        connectionStatus = (TextView) findViewById(R.id.connectionStatus);
        //writeMsg = (EditText) findViewById(R.id.writeMsg);

        btnProcessImage = (Button) findViewById(R.id.processImage);
        btnDisplay = (Button) findViewById(R.id.display);
        btnUpload = (Button) findViewById(R.id.upload);
        displayImage = (ImageView) findViewById(R.id.displayImage);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(wifiManager.isWifiEnabled())
            btnOnOff.setText("Wifi is ON");
        else
            btnOnOff.setText("Wifi is OFF");

        // this class provides the API for managing WI-Fi p2p connectivity
        mManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        // a chnnel that connects the application to the wifi p2p framework
        // most p2p operations require a Channel as an argument
        mChannel = mManager.initialize(this,getMainLooper(),null);

        mReceiver = new WiFiDirectBroadcastReciver(mManager,mChannel,this);

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }



    public class ListViewItemClicked implements android.widget.AdapterView.OnItemClickListener {
        @Override
        public
        void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final WifiP2pDevice device = deviceArray[position];
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = device.deviceAddress;

            mManager.connect(mChannel, config, new WifiP2pManager.ActionListener() {
                @Override
                public
                void onSuccess() {
                    Toast.makeText(getApplicationContext(), "Connected to "+device.deviceName, Toast.LENGTH_SHORT).show();
                }

                @Override
                public
                void onFailure(int reason) {
                    Toast.makeText(getApplicationContext(), "Not connected", Toast.LENGTH_SHORT).show();
                }
            });

        }
    }

    public class ServerClass extends Thread{
        Socket socket;
        ServerSocket serverSocket;

        @Override
        public
        void run() {
            try {
                serverSocket= new ServerSocket(8888);
                socket = serverSocket.accept();
                sendReceive = new SendReceive(socket);
                sendReceive.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    //*****************//*****************//*****************//*****************
    private class SendReceive extends Thread{
        private Socket socket;
        private InputStream inputStream;
        private OutputStream outputStream;

        public SendReceive(Socket skt){
            socket = skt;

            try {
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public
        void run() {
            byte[] buffer = new byte[1000];
            byte[] sizeBuffer = new byte[4];
            int bytes =0;
            int totalSize = 0;
            String tempMsg = new String();
            int count=0;
            int dataSize = 0;
            //int size = inputStream.read

            while (socket != null){
                if(isHost){

                    try {
                        bytes = inputStream.read(buffer);
                        if (bytes > 0){
                            Log.i("GOT RESULTS", "GOT RESULTS");
                            handler.obtainMessage(MESSAGE_READ, bytes,-1,buffer).sendToTarget();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }else if(isClient){
                    try {
                        //bytes = inputStream.read(buffer);
                        if(count == 0){
                            inputStream.read(sizeBuffer);
                            dataSize = ByteBuffer.wrap(sizeBuffer).getInt();
                            count = 1;
                        }else if(count == 1){
                            while(totalSize < dataSize) {
                                bytes = inputStream.read(buffer);
                                String temp = new String(buffer, 0, bytes);
                                totalSize +=bytes;
                                tempMsg += temp;
                                //Toast.makeText(getApplicationContext(),"IN SOCKET", Toast.LENGTH_SHORT).show();
                            }
                            handler.obtainMessage(MESSAGE_READ, totalSize,-1,tempMsg.getBytes()).sendToTarget();
                            tempMsg = new String();
                            totalSize =0;
                            bytes=0;
                            count=0;
                            dataSize=0;
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        //*****************//*****************//*****************//*****************
        public void write(byte[] bytes){
            try {
                outputStream.write(bytes, 0,bytes.length);
                Log.i("SENT", "-----SENT-----");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Client
    public class ClientClass extends Thread{
        Socket socket;
        String hostAdd;

        public ClientClass(InetAddress hostAddress){
            hostAdd = hostAddress.getHostAddress();
            socket = new Socket();
        }

        @Override
        public
        void run() {
            try {
                socket.connect(new InetSocketAddress(hostAdd, 8888),500);
                sendReceive = new SendReceive(socket);
                sendReceive.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    // send the uplouded Image here is Host, Send result is Client
    //@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@writeMsg@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
    //*****************//*****************//*****************//*****************
    private class BottonSendListener implements View.OnClickListener {
        @Override
        public
        void onClick(View view) {
            if(isHost){

                byte[] imageBytes;
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream);
                imageBytes = stream.toByteArray();

                // get the base 64 string
                String imgString = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                Toast.makeText(getApplicationContext(), Integer.toString(imageBytes.length), Toast.LENGTH_SHORT).show();
                Toast.makeText(getApplicationContext(), Integer.toString(imgString.getBytes().length), Toast.LENGTH_SHORT).show();

                int size = imgString.getBytes().length;;
                byte[] bytes = ByteBuffer.allocate(4).putInt(size).array();

                sendReceive.write(bytes);
                sendReceive.write(imgString.getBytes());
                Toast.makeText(getApplicationContext(),"Image is sent", Toast.LENGTH_SHORT).show();

            }else if(isClient){
                //Todo: send image processing results
                // send results
                Log.i("sendReceive", "sendReceive");
                Toast.makeText(getApplicationContext(),"Result", Toast.LENGTH_SHORT).show();
                Log.i("finalImageResult", finalImageResult);
                sendReceive.write(finalImageResult.getBytes());
            }
        }
    }

    // Host
    private class UploadImageListener implements View.OnClickListener {
        @Override
        public
        void onClick(View v) {
            Intent intent = new Intent();
            intent.setType("image/*");
            intent.setAction(Intent.ACTION_GET_CONTENT);
            startActivityForResult(Intent.createChooser(intent,"Select Picture"), PICK_IMAGE_REQUEST);
        }
    }

    // Host
    private class DisplayResultListener implements View.OnClickListener {
        @Override
        public
        void onClick(View v) {
            Log.i("DisplayResultListener", "DisplayResultListener");
            Intent intent = new Intent(MainActivity.this, edu.uga.cs.ada.Display.class);
            intent.putExtra("finalImageResult", finalImageResult);
            startActivity(intent);
        }
    }

    // Client
    private class ProcessImageListener implements View.OnClickListener {
        @Override
        public
        void onClick(View v) {
            /*
            Intent shareIntent = new Intent();
            shareIntent.setAction(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, filePath);
            shareIntent.setType("image/*");
            shareIntent.setPackage("edu.uga.cs.myapplication");
            //startActivityForResult(Intent.createChooser(shareIntent, "Share images to.."),SEND_IMAGE_FOR_PROCESSING);

            startActivityForResult(shareIntent, SEND_IMAGE_FOR_PROCESSING);*/
            finalImageResult = ProcessImage(filePath);

        }
    }

    private String ProcessImage(Uri filePath) {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), filePath);
            bitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false);
            final List<Classifier.Recognition> results = classifier.recognizeImage(bitmap);

            return results.toString();
        }
        catch (Exception e){

        }
        return "-";
    }

    private void initTensorFlowAndLoadModel() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    classifier = TensorFlowImageClassifier.create(
                            getAssets(),
                            MODEL_PATH,
                            LABEL_PATH,
                            INPUT_SIZE);
                } catch (final Exception e) {
                    throw new RuntimeException("Error initializing TensorFlow!", e);
                }
            }
        });
    }

    // first if for Host, second client
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i("RESULTReceiced", "RESULT Receiced");
        if(requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData()!= null){

            filePath = data.getData();

            try {
                bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(),filePath);
                displayImage.setImageBitmap(bitmap);

            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if(resultCode == RESULT_OK ){
            Log.i("LOAD", "LOAD");
            Bundle bundle = data.getExtras();
            requestCode = bundle.getInt("requestCode");
            if(requestCode == SEND_IMAGE_FOR_PROCESSING ) {
                Log.i("RESULTReceiced", "RESULT Receiced");
                //finalImageResult = bundle.get("finalImageResult"); // FOR OBJECT
                finalImageResult = bundle.getString("finalImageResult");
                Log.i("RESULTReceiced", finalImageResult);
                Toast.makeText(getApplicationContext(), "Result is back", Toast.LENGTH_SHORT);
            }
        }
    }

    // Client
    private void saveImage(Bitmap finalBitmap) {
        long yourmilliseconds = System.currentTimeMillis();
        SimpleDateFormat sdf = new SimpleDateFormat("MMM_dd_yyyy_HH_mm");
        Date resultdate = new Date(yourmilliseconds);
        String time = sdf.format(resultdate);
        fileName = "ProcessImage_" + time+ ".jpg";

        String root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString()+ "/Camera/ProcessImage";
        File myDir = new File(root);
        myDir.mkdirs();


        File file = new File(myDir, fileName);
        filePath = Uri.fromFile(file);
        Toast.makeText(getApplicationContext(), filePath.toString(), Toast.LENGTH_SHORT);

        if (file.exists()) file.delete();
        Log.i("FILEPATH", filePath.toString());
        Log.i("LOAD", root + fileName);
        try {
            FileOutputStream out = new FileOutputStream(file);
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected
    void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);


        //savedInstanceState.putParcelable("wifiManager", (Parcelable) wifiManager);
        //savedInstanceState.putSerializable("mManager", (Serializable) mManager);
        //savedInstanceState.putSerializable("mChannel", (Serializable) mChannel);
        //savedInstanceState.putSerializable("mReceiver", (Serializable) mReceiver);
        //savedInstanceState.putSerializable("mIntentFilter", (Serializable) mIntentFilter);

        savedInstanceState.putSerializable("peers", (Serializable) peers);
        savedInstanceState.putStringArray("deviceNameArray", deviceNameArray);
        savedInstanceState.putSerializable("deviceArray", deviceArray);

        //savedInstanceState.putSerializable("serverClass", (Serializable) serverClass);
        //savedInstanceState.putSerializable("clientClass", (Serializable) clientClass);
        //savedInstanceState.putSerializable("sendReceive", (Serializable) sendReceive);

        savedInstanceState.putBoolean("isHost", isHost);
        savedInstanceState.putBoolean("isClient", isClient);

        //savedInstanceState.putSerializable("isHost", (Serializable) filePath);
        savedInstanceState.putParcelable("bitmap", bitmap);
        savedInstanceState.putParcelable("decodedByte", decodedByte);

        savedInstanceState.putString("fileName", fileName);
        savedInstanceState.putString("finalImageResult", finalImageResult);
        Log.d("STATE-SAVE", "onSaveInstanceState()");

    }

    @Override
    protected
    void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        if (savedInstanceState != null) {

            //wifiManager = (WifiManager) savedInstanceState.getSerializable("wifiManager");
            //mManager = (WifiP2pManager) savedInstanceState.getSerializable("mManager");
            //mChannel = (WifiP2pManager.Channel) savedInstanceState.getSerializable("mChannel");
            //mReceiver = (BroadcastReceiver) savedInstanceState.getSerializable("mReceiver" );
            //mIntentFilter = (IntentFilter) savedInstanceState.getSerializable("mIntentFilter");

            peers = (List<WifiP2pDevice>) savedInstanceState.getSerializable("peers");
            deviceNameArray = savedInstanceState.getStringArray("deviceNameArray");
            deviceArray = (WifiP2pDevice[]) savedInstanceState.getSerializable("deviceArray");

            //serverClass = (ServerClass) savedInstanceState.getSerializable("serverClass");
            //clientClass = (ClientClass) savedInstanceState.getSerializable("clientClass");
            //sendReceive = (SendReceive) savedInstanceState.getSerializable("sendReceive");

            isHost = savedInstanceState.getBoolean("isHost");
            isClient = savedInstanceState.getBoolean("isClient");

            //filePath = (Uri) savedInstanceState.getSerializable("isHost");
            bitmap = savedInstanceState.getParcelable("bitmap");
            decodedByte = savedInstanceState.getParcelable("decodedByte" );

            fileName = savedInstanceState.getString("fileName");
            finalImageResult = savedInstanceState.getString("finalImageResult");


            Log.d("RESTORING...", "onRestoreInstanceState()");
        } else {
            Log.d("SavedInstanceState", "null");
        }
    }

    @Override
    protected
    void onStart() {
        super.onStart();
        Log.d("onStart()", "onStart()");
    }

    @Override
    protected
    void onStop() {
        super.onStop();
        Log.d("onStop()", "onStop()");
    }

    @Override
    protected
    void onDestroy() {
        super.onDestroy();
        Log.d("onDestroy()", "onDestroy()");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                classifier.close();
            }
        });
    }

}
