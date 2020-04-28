/*
 * Copyright 2018, Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hipstershop;

import hipstershop.Demo.Ad;
import hipstershop.Demo.AdRequest;
import hipstershop.Demo.AdResponse;
import hipstershop.OpenTelemetryUtils.HttpTextFormatClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.OpenTelemetry;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.Status;
import io.opentelemetry.trace.Tracer;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** A simple client that requests ads from the Ads Service. */
public class AdServiceClient {

  private static final Logger logger = LogManager.getLogger(AdServiceClient.class);
  private static final Tracer tracer = OpenTelemetry.getTracerProvider().get("AdServiceClient");

  private final ManagedChannel channel;
  private final hipstershop.AdServiceGrpc.AdServiceBlockingStub blockingStub;

  /** Construct client connecting to Ad Service at {@code host:port}. */
  private AdServiceClient(String host, int port) {
    this(
        ManagedChannelBuilder.forAddress(host, port)
            // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
            // needing certificates.
            .usePlaintext()
            .intercept(new HttpTextFormatClientInterceptor())
            .build());
  }

  /** Construct client for accessing RouteGuide server using the existing channel. */
  private AdServiceClient(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = hipstershop.AdServiceGrpc.newBlockingStub(channel);
  }

  private void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Get Ads from Server. */
  public void getAds(String contextKey) {
    logger.info("Get Ads with context " + contextKey + " ...");
    AdRequest request = AdRequest.newBuilder().addContextKeys(contextKey)
        .build();

    //todo: check out grpc semantic conventions and apply them
    Span span = tracer
        .spanBuilder("AdsClient")
        .startSpan();
    AdResponse response;
    try (Scope ignored = tracer.withSpan(span)) {
      tracer.getCurrentSpan().addEvent("Getting Ads");

      response = blockingStub.getAds(request);
      tracer.getCurrentSpan().addEvent("Received response from Ads Service.");
    } catch (StatusRuntimeException e) {
      tracer.getCurrentSpan().setStatus(convertStatus(e.getStatus()));
      logger.log(Level.WARN, "RPC failed: " + e.getStatus());
      return;
    } finally {
      span.end();
    }
    for (Ad ads : response.getAdsList()) {
      logger.info("Ads: " + ads.getText());
    }
  }

  private Status convertStatus(io.grpc.Status status) {
    if (status.isOk()) {
      return Status.OK;
    }
    //todo: check out grpc semantic conventions and apply them
    return Status.UNKNOWN.withDescription(status.getDescription());
  }

  private static int getPortOrDefaultFromArgs(String[] args) {
    int portNumber = 9555;
    if (2 < args.length) {
      try {
        portNumber = Integer.parseInt(args[2]);
      } catch (NumberFormatException e) {
        logger.warn(String.format("Port %s is invalid, use default port %d.", args[2], 9555));
      }
    }
    return portNumber;
  }

  private static String getStringOrDefaultFromArgs(
      String[] args, int index, @Nullable String defaultString) {
    String s = defaultString;
    if (index < args.length) {
      s = args[index];
    }
    return s;
  }

  /**
   * Ads Service Client main. If provided, the first element of {@code args} is the context key to
   * get the ads from the Ads Service
   */
  public static void main(String[] args) throws InterruptedException {
    // Add final keyword to pass checkStyle.
    final String contextKeys = getStringOrDefaultFromArgs(args, 0, "camera");
    final String host = getStringOrDefaultFromArgs(args, 1, "localhost");
    final int serverPort = getPortOrDefaultFromArgs(args);

    OpenTelemetryUtils.initializeSdk("AdServiceClient");

    AdServiceClient client = new AdServiceClient(host, serverPort);
    try {
      for (int i = 0; i < 10; i++) {
        client.getAds(contextKeys);
      }
    } finally {
      client.shutdown();
      OpenTelemetrySdk.getTracerProvider().shutdown();
    }

    logger.info("Exiting AdServiceClient...");
  }

}
