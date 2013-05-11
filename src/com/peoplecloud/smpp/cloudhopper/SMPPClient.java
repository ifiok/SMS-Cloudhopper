package com.peoplecloud.smpp.cloudhopper;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.EnquireLinkResp;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.peoplecloud.smpp.api.SMSMessageListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("rawtypes")
public class SMPPClient {
	private static final Logger logger = LoggerFactory
			.getLogger(SMPPClient.class);

	private boolean requestDeliveryReceipt;
	private boolean isRunning;
	private SmppSession smppSession;

	private Properties configProperties;

	private Thread mSMSCConnMonitorThread;
	private ThreadPoolExecutor executor;
	private ScheduledThreadPoolExecutor monitorExecutor;
	private DefaultSmppClient clientBootstrap;

	private List<SMSMessageListener> listOfMessageListeners;

	public static void main(String[] args) throws Exception {
		SMPPClient lSMPPClient = new SMPPClient(getDefaultProperties());
		lSMPPClient.initialize();

		System.out.println("Usage:: Msg, From, To");
		lSMPPClient.sendSMSMessage(args[0], args[1], args[2]);
	}

	public SMPPClient(Properties aProps) {
		configProperties = aProps;
		listOfMessageListeners = new ArrayList<SMSMessageListener>();
	}

	public void initialize() {
		requestDeliveryReceipt = Boolean.parseBoolean(configProperties
				.getProperty("smsc.server.requestdeliveryreceipt"));
		//
		// setup 3 things required for any session we plan on creating
		//

		// For monitoring thread use, it's preferable to create your own
		// instance of an executor with Executors.newCachedThreadPool() and cast
		// it to ThreadPoolExecutor this permits exposing thinks like
		// executor.getActiveCount() via JMX possible no point renaming the
		// threads in a factory since underlying Netty framework does not easily
		// allow you to customize your thread names.
		executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

		// to enable automatic expiration of requests, a second scheduled
		// executor is required which is what a monitor task will be executed
		// with - this is probably a thread pool that can be shared with between
		// all client bootstraps
		monitorExecutor = (ScheduledThreadPoolExecutor) Executors
				.newScheduledThreadPool(1, new ThreadFactory() {
					private AtomicInteger sequence = new AtomicInteger(0);

					@Override
					public Thread newThread(Runnable r) {
						Thread t = new Thread(r);
						t.setName("SmppClientSessionWindowMonitorPool-"
								+ sequence.getAndIncrement());
						return t;
					}
				});

		// a single instance of a client bootstrap can technically be shared
		// between any sessions that are created (a session can go to any
		// different number of SMSCs) - each session created under a client
		// bootstrap will use the executor and monitorExecutor set in its
		// constructor - just be *very* careful with the "expectedSessions"
		// value to make sure it matches the actual number of total concurrent
		// open sessions you plan on handling - the underlying netty library
		// used for NIO sockets essentially uses this value as the max number of
		// threads it will ever use, despite the "max pool size", etc. set on
		// the executor passed in here
		clientBootstrap = new DefaultSmppClient(
				Executors.newCachedThreadPool(), 1, monitorExecutor);

		//
		// setup configuration for a client session
		//
		bindSMPPSession();
	}

	/**
	 * Could either implement SmppSessionHandler or only override select methods
	 * by extending a DefaultSmppSessionHandler.
	 */
	public static class ClientSmppSessionHandler extends
			DefaultSmppSessionHandler {

		private List<SMSMessageListener> listOfListeners;

		public ClientSmppSessionHandler(
				List<SMSMessageListener> aListOfListeners) {
			super(logger);
			listOfListeners = aListOfListeners;
		}

		@Override
		public void firePduRequestExpired(PduRequest pduRequest) {
			logger.warn("PDU request expired: [ " + pduRequest
					+ " ] [ Command ID : " + pduRequest.getCommandId()
					+ ", Command Status : " + pduRequest.getCommandStatus()
					+ ", Command Length : " + pduRequest.getCommandStatus()
					+ " ]");
		}

		@Override
		public PduResponse firePduRequestReceived(PduRequest pduRequest) {
			PduResponse response = pduRequest.createResponse();

			// do any logic here
			if (logger.isDebugEnabled()) {
				logger.debug("PDU request received: [ " + pduRequest
						+ " ], Command ID: " + pduRequest.getCommandId());
			}

			if (pduRequest.getCommandId() == SmppConstants.CMD_ID_DELIVER_SM) {
				DeliverSm mo = (DeliverSm) pduRequest;

				Address sourceAddress = mo.getSourceAddress();
				Address destAddress = mo.getDestAddress();
				byte[] shortMessage = mo.getShortMessage();
				String sms = new String(shortMessage);

				if (logger.isDebugEnabled()) {
					logger.debug("Received Message is :: " + sms + ", from :: "
							+ sourceAddress.getAddress());
				}

				for (SMSMessageListener lListener : listOfListeners) {
					lListener.notify(sms, sourceAddress.getAddress(),
							destAddress.getAddress());
				}
			}

			return response;
		}
	}

	public String sendSMSMessage(String aMessage, String aSentFromNumber,
			String aSendToNumber) {
		byte[] textBytes = CharsetUtil
				.encode(aMessage, CharsetUtil.CHARSET_GSM);

		try {
			SubmitSm submitMsg = new SubmitSm();

			// add delivery receipt if enabled.
			if (requestDeliveryReceipt) {
				submitMsg
						.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
			}

			submitMsg.setSourceAddress(new Address((byte) 0x03, (byte) 0x00,
					aSentFromNumber));
			submitMsg.setDestAddress(new Address((byte) 0x01, (byte) 0x01,
					aSendToNumber));
			submitMsg.setShortMessage(textBytes);

			logger.debug("About to send message to " + aSendToNumber
					+ ", Msg is :: " + aMessage + ", from :: "
					+ aSentFromNumber);

			SubmitSmResp submitResp = smppSession.submit(submitMsg, 15000);
			logger.debug("Message sent to " + aSendToNumber
					+ " with message id " + submitResp.getMessageId());
			return "Message ID - " + submitResp.getMessageId();
		} catch (Exception ex) {
			logger.error("Exception sending message [Msg, From, To] :: ["
					+ aMessage + ", " + aSentFromNumber + ", " + aSendToNumber
					+ "]");
		}

		logger.debug("Message **NOT** sent to " + aSendToNumber);
		return "Message Not Submitted to " + aSendToNumber;
	}

	public void registerListener(SMSMessageListener aMsgListener) {
		listOfMessageListeners.add(aMsgListener);
	}

	public void setListOfMessageListeners(List<SMSMessageListener> aListenerList) {
		listOfMessageListeners = aListenerList;
	}

	public List<SMSMessageListener> getListOfMessageListeners() {
		return listOfMessageListeners;
	}

	public void bindSMPPSession() {
		DefaultSmppSessionHandler sessionHandler = new ClientSmppSessionHandler(
				listOfMessageListeners);

		SmppSessionConfiguration smppSessionConfig = new SmppSessionConfiguration();
		smppSessionConfig.setWindowSize(1);
		smppSessionConfig.setName("SMPP Session 1");
		smppSessionConfig.setType(SmppBindType.TRANSCEIVER);
		smppSessionConfig.setConnectTimeout(10000);
		smppSessionConfig.getLoggingOptions().setLogBytes(true);

		smppSessionConfig.setHost(configProperties
				.getProperty("smsc.server.host"));
		smppSessionConfig.setPort(Integer.parseInt(configProperties
				.getProperty("smsc.server.port")));
		smppSessionConfig.setSystemId(configProperties
				.getProperty("smsc.server.systemid"));
		smppSessionConfig.setPassword(configProperties
				.getProperty("smsc.server.password"));

		// to enable monitoring (request expiration)
		smppSessionConfig.setRequestExpiryTimeout(30000);
		smppSessionConfig.setWindowMonitorInterval(15000);
		smppSessionConfig.setCountersEnabled(true);

		//
		// create session, enquire link, submit an sms, close session
		//
		try {
			// create a session by having the bootstrap connect a
			// socket, send the bind request, and wait for a bind response
			smppSession = clientBootstrap.bind(smppSessionConfig,
					sessionHandler);

			// send periodic requests to keep session alive.
			startAsynchronousSMPPConnectionMonitor();
		} catch (Exception e) {
			logger.error(
					"Error occured while binding smpp session. Cannot send or receive any messages. Error is : "
							+ e.getMessage(), e);
		}
	}

	private void startAsynchronousSMPPConnectionMonitor() {

		if (mSMSCConnMonitorThread != null && mSMSCConnMonitorThread.isAlive()) {
			return;
		}

		mSMSCConnMonitorThread = new Thread(new Runnable() {
			public void run() {
				while (isRunning) {
					try {
						// "asynchronous" enquireLink call - send it, get a
						// future, and then optionally choose to pick when we
						// wait for it
						WindowFuture<Integer, PduRequest, PduResponse> enquireLinkFuture = smppSession
								.sendRequestPdu(new EnquireLink(), 100000, true);

						if (!enquireLinkFuture.await()) {
							logger.warn("Failed to receive enquire_link_resp within specified time");
						} else if (enquireLinkFuture.isSuccess()) {
							EnquireLinkResp enquireLinkResp = (EnquireLinkResp) enquireLinkFuture
									.getResponse();
							logger.warn("enquire link response: commandStatus ["
									+ enquireLinkResp.getCommandStatus()
									+ "="
									+ enquireLinkResp.getResultMessage() + "]");
						} else {
							logger.warn("Failed to properly receive enquire link response: "
									+ enquireLinkFuture.getCause());
						}

						// Wait for the timeout interval before rechecking.
						try {
							Thread.sleep(Long.parseLong(configProperties
									.getProperty("smpp.session.enquirelink.interval")));
						} catch (InterruptedException ie) {
							// Ignore.
						}
					} catch (Exception e) {
						logger.error(
								"Exception occured while waiting for enquire link response: "
										+ e.getMessage(), e);
					}
				}
			}
		});

		mSMSCConnMonitorThread.start();
	}

	public void releaseSMPPSession() {
		logger.info("Releasing SMPP Session ...");
		smppSession.unbind(5000);
	}

	public void shutdown() {
		// Stop the enquire link thread.
		isRunning = false;
		mSMSCConnMonitorThread.interrupt();
		mSMSCConnMonitorThread = null;

		try {
			// Wait for the enquire link timeout at most (Not sure if its
			// required as i have already interrupted the thread)
			Thread.sleep(Long.parseLong(configProperties
					.getProperty("smpp.session.enquirelink.interval")));
		} catch (InterruptedException ie) {
			// Ignore.
		}

		// this is required to not causing server to hang from non-daemon
		// threads this also makes sure all open Channels are closed to I
		// *think*
		logger.info("Releasing SMPP Session and shutting down client bootstrap and executors...");

		releaseSMPPSession();

		if (smppSession != null) {
			logger.info("Cleaning up session... (logging final counters)");

			if (smppSession.hasCounters()) {
				logger.info("tx-enquireLink :: "
						+ smppSession.getCounters().getTxEnquireLink());
				logger.info("tx-submitSM :: "
						+ smppSession.getCounters().getTxSubmitSM());
				logger.info("tx-deliverSM :: "
						+ smppSession.getCounters().getTxDeliverSM());
				logger.info("tx-dataSM :: "
						+ smppSession.getCounters().getTxDataSM());
				logger.info("rx-enquireLink :: "
						+ smppSession.getCounters().getRxEnquireLink());
				logger.info("rx-submitSM :: "
						+ smppSession.getCounters().getRxSubmitSM());
				logger.info("rx-deliverSM :: "
						+ smppSession.getCounters().getRxDeliverSM());
				logger.info("rx-dataSM :: "
						+ smppSession.getCounters().getRxDataSM());
			}

			smppSession.destroy();
			// alternatively, could call close(), get outstanding requests from
			// the sendWindow (if we wanted to retry them later), then call
			// shutdown()
		}

		clientBootstrap.destroy();
		executor.shutdownNow();
		monitorExecutor.shutdownNow();
	}

	public static Properties getDefaultProperties() {
		Properties lDefaultProps = new Properties();
		lDefaultProps.put("smsc.server.host", "10.5.210.201");
		lDefaultProps.put("smsc.server.port", "5815");
		lDefaultProps.put("smsc.server.systemid", "ZenithFree");
		lDefaultProps.put("smsc.server.password", "ztpass");

		lDefaultProps.put("smsc.server.requestdeliveryreceipt", "false");
		lDefaultProps.put("smpp.session.enquirelink.interval", "30000");

		return lDefaultProps;
	}
}