

package tw.imonkey.io;

import android.app.Activity;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import de.greenrobot.event.EventBus;

public class MainActivity extends Activity {
    public static final String devicePrefs = "devicePrefs";
    int dataCount;
    int limit=1000;//max Logs (even number)

    String PiGPIO[]={"BCM4","BCM17","BCM27","BCM22","BCM5","BCM6","BCM13","BCM19",
            //pin#    7    ,11     ,13     ,15     ,29    ,31    ,33     ,35
            //Name    X00  ,X01    ,X02    ,X03    ,X04   ,X05   ,X06    ,X07
                     "BCM18","BCM23","BCM24","BCM25","BCM12","BCM16","BCM20","BCM21"};
            //pin#    12    ,16     ,18     ,24     ,32     ,36     ,38     ,40
            //Name    Y00   ,Y01    ,Y02    ,Y03    ,Y04    ,Y05    ,Y06    ,Y07
    String GPIOName[]={"X00","X01","X02","X03","X04","X05","X06","X07",
                        "Y00","Y01","Y02","Y03","Y04","Y05","Y06","Y07"};
    Gpio[] GPIO=new Gpio[16];
    Map<String,Gpio> GPIOMap=new HashMap<>();
    Gpio RESETGpio;
    String RESET="BCM26";
           //pin#    37
    String memberEmail,deviceId;
    Map<String, Object> input = new HashMap<>();
    Map<String, Object> log = new HashMap<>();
    Map<String, Object> alert = new HashMap<>();
    ArrayList<String> users = new ArrayList<>();
    DatabaseReference mSETTINGS , mAlert, mLog, mXINPUT,mYOUTPUT,mUsers,presenceRef,connectedRef;
    int logCount ;

    public MySocketServer mServer;
    private static final int SERVER_PORT = 9402;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init();
        deviceOnline();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        if ( RESETGpio != null) {
            try {
                RESETGpio.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                RESETGpio = null;
            }
        }
        for(String PiGPIO:GPIOMap.keySet()) {
            if (GPIOMap.get(PiGPIO) != null) {
                try {
                    GPIOMap.get(PiGPIO).close();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    GPIOMap.remove(PiGPIO);
                }
            }
        }
    }

    private  void init(){
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
        EventBus.getDefault().register(this);
        SharedPreferences settings = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE);
        memberEmail = settings.getString("memberEmail", null);
        deviceId = settings.getString("deviceId", null);
        logCount = settings.getInt("logCount",0);

        if (memberEmail == null) {
            memberEmail = "test@po-po.com";
            deviceId = "RPI3_IO_test";
            DatabaseReference mAddTestDevice=FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId);
            Map<String, Object> addTest = new HashMap<>();
            addTest.put("companyId","po-po") ;
            addTest.put("device","rpi3IOtest");
            addTest.put("deviceType","GPIO智慧機"); //GPIO智慧機
            addTest.put("description","Android things rpi3IO test");
            addTest.put("masterEmail",memberEmail) ;
            addTest.put("timeStamp", ServerValue.TIMESTAMP);
            addTest.put("topics_id",deviceId);
            Map<String, Object> user = new HashMap<>();
            user.put(memberEmail.replace(".","_"),memberEmail);
            addTest.put("users",user);
            mAddTestDevice.setValue(addTest);
            startServer();
        }
        mSETTINGS = FirebaseDatabase.getInstance().getReference("/DEVICE/" + deviceId + "/SETTINGS");
        mAlert= FirebaseDatabase.getInstance().getReference("/DEVICE/"+ deviceId + "/alert");
        mLog=FirebaseDatabase.getInstance().getReference("/DEVICE/" + deviceId + "/LOG/");
        mXINPUT = FirebaseDatabase.getInstance().getReference("/DEVICE/" + deviceId + "/X/");
        mYOUTPUT = FirebaseDatabase.getInstance().getReference("/DEVICE/" + deviceId+ "/Y/");
        mUsers= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/users/");
        mUsers.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                users.clear();
                for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                    users.add(childSnapshot.getValue().toString());
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
        PeripheralManagerService service = new PeripheralManagerService();
        try {
            RESETGpio = service.openGpio(RESET);
            RESETGpio.setDirection(Gpio.DIRECTION_IN);
            RESETGpio.setEdgeTriggerType(Gpio.EDGE_BOTH);
            RESETGpio.registerGpioCallback(new GpioCallback() {
                @Override
                public boolean onGpioEdge(Gpio gpio) {
                    try {
                        if (RESETGpio.getValue()){
                            startServer();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    return true;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (int i=0;i<16;i++) {
            final int index = i;
            // X00->INPUT
            if (GPIOName[i].charAt(0) == 'X') {
                try {
                    GPIO[index] = service.openGpio(PiGPIO[index]);
                    GPIO[index].setDirection(Gpio.DIRECTION_IN);
                    GPIO[index].setEdgeTriggerType(Gpio.EDGE_BOTH);
                    GPIO[index].registerGpioCallback(new GpioCallback() {
                        @Override
                        public boolean onGpioEdge(Gpio gpio) {
                            try {
                                input.clear();
                                input.put(GPIOName[index], GPIO[index].getValue());
                                input.put("memberEmail", "Device");
                                input.put("timeStamp", ServerValue.TIMESTAMP);
                               // mXINPUT.child(GPIOName[index]).push().setValue(input);
                                mXINPUT.child(GPIOName[index]).setValue(input);
                                alert(GPIOName[index]+"="+GPIO[index].getValue());
                                log(GPIOName[index]+"="+GPIO[index].getValue());

                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            return true;
                        }
                        @Override
                        public void onGpioError(Gpio gpio, int error) {
                            super.onGpioError(gpio, error);
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            // Y00->OUTPUT
            else if (GPIOName[i].charAt(0) == 'Y') {
                try {
                    GPIO[index] = service.openGpio(PiGPIO[index]);
                    GPIO[index].setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
                    GPIO[index].setValue(false);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            GPIOMap.put(GPIOName[index], GPIO[index]);
        }
//YoutputListener
        for (int i=0;i<16;i++) {
            if (GPIOName[i].charAt(0) == 'Y') {
                mYOUTPUTListener(GPIOName[i]);
            }
        }
    }
    private void mYOUTPUTListener(final String outpin){
        mYOUTPUT.child(outpin).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.child(outpin).getValue()!=null) {
                    if (snapshot.child(outpin).getValue().equals(true)) {
                        try {
                            GPIOMap.get(outpin).setValue(true);
                            alert(outpin + "=" + true);
                            log(outpin + "=" + true);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    } else {
                        try {
                            GPIOMap.get(outpin).setValue(false);
                            log(outpin + "=" + false);
                            alert(outpin + "=" + false);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }
    private void alert(final String message){
        mSETTINGS.child("notify").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
               if(snapshot.child("SMS").getValue()!=null) {
                   for (String email : users) {
                       if (!email.contains("test")) {
                           NotifyUser.SMSPUSH(deviceId, email, message);
                       }
                   }
               }
                if (snapshot.child("EMAIL").getValue() != null) {
                    for (String email : users) {
                        if (!email.contains("test")) {
                            NotifyUser.emailPUSH(deviceId, email, message);
                        }
                    }
                }
                if (snapshot.child("PUSH").getValue() != null) {
                    for (String email : users) {
                        NotifyUser.IIDPUSH(deviceId, email, "智慧機通知", message);
                    }
                    NotifyUser.topicsPUSH(deviceId, memberEmail, "智慧機通知", message);
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });

        alert.clear();
        alert.put("message","Device:"+message);
        alert.put("timeStamp", ServerValue.TIMESTAMP);
        mAlert.setValue(alert);
    }
    private void log(String message) {
        log.clear();
        log.put("message", "Device:"+message);
        log.put("memberEmail", memberEmail);
        log.put("timeStamp", ServerValue.TIMESTAMP);
        mLog.push().setValue(log);
        logCount++;
        if (logCount>(limit+(limit)/2)){
            dataLimit(mLog,limit);
            logCount=limit;
        }
        SharedPreferences.Editor editor = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE).edit();
        editor.putInt("logCount",logCount);
        editor.apply();
    }
    private void dataLimit(final DatabaseReference mData,int limit) {
        mData.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                dataCount=(int)(snapshot.getChildrenCount());
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });
        if((dataCount-limit)>0) {
            mData.orderByKey().limitToFirst(dataCount - limit)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot snapshot) {
                            for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                                mData.child(childSnapshot.getKey()).removeValue();
                            }
                        }

                        @Override
                        public void onCancelled(DatabaseError databaseError) {
                        }
                    });
        }
    }
    //device online check
    private void deviceOnline(){
        presenceRef=FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/connection");//for log activity
        presenceRef.setValue(true);
        presenceRef.onDisconnect().setValue(null);
        connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectedRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                if (connected) {
                    presenceRef.setValue(true);
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {
            }
        });
    }

    // websocket server
    private void startServer() {
        InetAddress inetAddress = getInetAddress();
        if (inetAddress == null) {
            return;
        }

        mServer = new MySocketServer(new InetSocketAddress(inetAddress.getHostAddress(), SERVER_PORT));
        mServer.start();
    }

    private static InetAddress getInetAddress() {
        try {
            for (Enumeration en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface networkInterface = (NetworkInterface) en.nextElement();

                for (Enumeration enumIpAddr = networkInterface.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = (InetAddress) enumIpAddr.nextElement();

                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress;
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }

        return null;
    }
    @SuppressWarnings("UnusedDeclaration")
    public void onEvent(SocketMessageEvent event) {
        String message = event.getMessage();
        String[] mArray = message.split(",");
        if (mArray.length==2) {
            SharedPreferences.Editor editor = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE).edit();
            editor.putString("memberEmail", mArray[0]);
            editor.putString("deviceId", mArray[1]);
            editor.apply();
            mServer.sendMessage("echo: " + message);
            alert("IO智慧機設定完成!");
            Intent i;
            i = new Intent(this,MainActivity.class);
            startActivity(i);
        }
    }
}

