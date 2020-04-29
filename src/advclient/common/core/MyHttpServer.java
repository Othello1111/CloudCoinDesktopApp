/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package global.cloudcoin.ccbank.core;

import advclient.AdvancedClient;
import advclient.AppUI;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import global.cloudcoin.ccbank.FrackFixer.GetTicketResponse;
import java.io.File;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.json.JSONException;

import com.sun.net.httpserver.HttpsServer;
import global.cloudcoin.ccbank.ServantManager.ServantManager;
import java.awt.RenderingHints.Key;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import sun.security.tools.keytool.CertAndKeyGen;
import sun.security.x509.X500Name;

/**
 *
 * @author 
 */
public class MyHttpServer {
    GLogger logger;
    String ltag = "HttpServer";
    int port;
    String password;
    String certFile;
    String keyFile;
    HttpsServer httpsServer;
    CloudBank cloudbank;
    
    public MyHttpServer(int port, String password, CloudBank cloudbank, GLogger logger) {
        this.logger = logger;
        this.port = port;
        this.password = password;
        this.certFile = AppCore.getRootPath() + File.separator + "mycert.crt";
        this.keyFile = AppCore.getRootPath() + File.separator + "mykey.jks";
        this.cloudbank = cloudbank;
        //ks.store( new FileOutputStream( "NewClientKeyStore" ), "MyPass".toCharArray() );
    }
    
    
    private boolean initKeystore() {
        File f = new File(this.keyFile);
        if (f.exists()) {
            logger.debug(ltag, "No need to init keystore");
            return true;
        }

        logger.debug(ltag, "Initializing keystore");
        
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, null);
            ks.store(new FileOutputStream(this.keyFile), this.password.toCharArray());

            CertAndKeyGen gen = new CertAndKeyGen("RSA","SHA1WithRSA");
            gen.generate(1024);

            PrivateKey key = gen.getPrivateKey();
            X509Certificate cert = gen.getSelfCertificate(new X500Name("CN=ROOT"), (long)365*24*3600);
            
            X509Certificate[] chain = new X509Certificate[1];
            chain[0] = cert;
            ks.setKeyEntry("mykey", key, password.toCharArray(), chain);  
            
            gen = new CertAndKeyGen("RSA","SHA1WithRSA");
            gen.generate(1024);

            cert = gen.getSelfCertificate(new X500Name("CN=SINGLE_CERTIFICATE"), (long)365*24*3600);
            ks.setCertificateEntry("single_cert", cert);            
            ks.store(new FileOutputStream(this.keyFile), this.password.toCharArray());
        } catch (Exception e) {
            logger.error(ltag, "Failed to init keys: " + e.getMessage());
            return false;
        }
        
        logger.debug(ltag, "Keystore inited");
        
        return true;
    }
    
    public boolean startServer() {
        if (!initKeystore()) {
            logger.error(ltag, "Failed");
            return false;
        }

        try {
            InetSocketAddress address = new InetSocketAddress("0.0.0.0", port);
            
            // Backlog 1 is crucial. No concurrency yet
            httpsServer = HttpsServer.create(address, 1);
            
            SSLContext sslContext = SSLContext.getInstance("TLS");
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(this.keyFile), this.password.toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, this.password.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                public void configure(HttpsParameters params) {
                    try {

                        SSLContext c = SSLContext.getDefault();
                        SSLEngine engine = c.createSSLEngine();
                        params.setNeedClientAuth(false);
                        params.setCipherSuites(engine.getEnabledCipherSuites());
                        params.setProtocols(engine.getEnabledProtocols());

                        // Get the default parameters
                        SSLParameters defaultSSLParameters = c.getDefaultSSLParameters();
                        params.setSSLParameters(defaultSSLParameters);
                    } catch (Exception ex) {
                        logger.debug(ltag, "Failed to configure HTTPs server: " + ex.getMessage());
                        
                    }
                }
            });
           
            ThreadPoolExecutor executor = new ThreadPoolExecutor(100, 200, 60, TimeUnit.SECONDS,  new ArrayBlockingQueue<Runnable>(10));
            
            httpsServer.createContext("/", new MyHandler(cloudbank, logger));
            httpsServer.setExecutor(executor);
            httpsServer.start();
        
            logger.debug(ltag, "Server is listening on https:/" + address.toString());
        } catch (UnknownHostException e) {
            logger.error(ltag, "Failed to get address: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error(ltag, "Failed to init server: " + e.getMessage());
            return false;
        }
        
       // HttpsServer httpsServer = HttpsServer.create(address, 0);
        
        return true;
    }
    
    public boolean stopServer() {
        logger.debug(ltag, "Stopping server");
        if (httpsServer != null)
            httpsServer.stop(1);
        
        return true;
    }

}

class MyHandler implements HttpHandler {
    public String ltag = "HTTPHandler";
    GLogger logger;
    CloudBank cloudbank;
    boolean completed, isError;
    String message;
    Wallet tmpWallet;
    
    public MyHandler(CloudBank cloudbank, GLogger logger) {
        this.logger = logger;
        this.cloudbank = cloudbank;
        this.completed = false;
    }
    @Override
    public synchronized void handle(HttpExchange t) throws IOException {
        String method = t.getRequestMethod();
        String uri = t.getRequestURI().toString();
        Map vars = new HashMap<String,String>();
        String route = t.getRequestURI().getPath();
        
        isError = false;
        message = "";
        
        logger.debug(ltag, method + " " + uri);
        if (!route.startsWith("/service/")) {
            sendError(t, "Invalid service requested");
            return;
        }

        if (method.equals("GET")) {
            String[] params = uri.split("\\?");
            route = params[0].substring(9);
            if (params.length > 1) {
                String[] kv = params[1].split("&");
                for (int i = 0; i < kv.length; i++) {
                    String[] parts = kv[i].split("=");
                    if (parts.length != 2) {
                        logger.debug(ltag, "Invalid parameter pair. Idx " + i);
                        sendError(t, "Invalid parameters in HTTP query");
                        return;
                    }
                    
                    vars.put(parts[0], parts[1]);
                }
            }
            
            tmpWallet = cloudbank.sm.getActiveWallet();
            cloudbank.startCloudbankService(route, vars, new CallbackInterface() {
                public void callback(Object o) {
                    CloudbankResult cr = (CloudbankResult) o;
                
                    if (cr.status == CloudbankResult.STATUS_ERROR) {
                        isError = true;                        
                    }
                    
                    System.out.println("Completed " + cr.status);
                    message = cr.message;
                    completed = true;
                }
            });
            /*
            Iterator it = vars.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry pair = (Map.Entry)it.next();
                System.out.println("-> " + pair.getKey() + " = " + pair.getValue());
            }
            */
        }
        
        int iterations = 0;
        while (!completed) {
            //System.out.println("sleep");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {}
            iterations++;
            if (iterations > Config.CLOUDBANK_MAX_ITERATIONS) {
                setWalletIfNessecary();
                sendError(t, "timeout");
                return;
            }
                
        }
        
        setWalletIfNessecary();
        
        completed = false;
        if (isError)
            sendError(t, message);
        else 
            sendResult(t, message);
    }
    
    private void sendError(HttpExchange httpExchange, String message) {
        sendResponse(httpExchange, 200, "error", message);
    }
    
    private void sendResult(HttpExchange httpExchange, String message) {
        sendResponse(httpExchange, 200, "pass", message);
    }
    
    private void sendResponse(HttpExchange httpExchange, int code, String status, String message) {
        OutputStream outputStream = httpExchange.getResponseBody();
        String dateStr = AppCore.getDate("" + (System.currentTimeMillis() /1000));
        StringBuilder sb = new StringBuilder();
        
        sb.append("{\"server\":\"");
        sb.append(AppUI.brand.getTitle(null));
        sb.append("\", \"status\":\"");
        sb.append(status);
        sb.append("\", \"message\":\"");
        sb.append(message);
        sb.append("\", \"time\":\"");
        sb.append(dateStr);
        sb.append("\", \"version\": \"");
        sb.append(AppUI.brand.getResultingVersion(AdvancedClient.version));
        sb.append("\"}");
        
        String response = sb.toString();
        
        try {
            httpExchange.sendResponseHeaders(code, response.length());
            outputStream.write(response.getBytes());
            outputStream.flush();
            outputStream.close();
        } catch (IOException e) {
            logger.error(ltag, "Failed to send response: " + e.getMessage());
            return;
        }
    }
    
    public void setWalletIfNessecary() {
        if (tmpWallet == null)
            return;
        
        Wallet w = cloudbank.sm.getActiveWallet();
        if (w.getName().equals(tmpWallet.getName())) {
            logger.debug(ltag, "Setting wallet back to " + tmpWallet.getName());
            cloudbank.sm.setActiveWalletObj(tmpWallet);
        }
    }
}
