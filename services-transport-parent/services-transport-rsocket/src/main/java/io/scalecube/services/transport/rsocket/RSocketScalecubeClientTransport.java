package io.scalecube.services.transport.rsocket;

import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.util.ByteBufPayload;
import io.scalecube.net.Address;
import io.scalecube.services.transport.api.ClientChannel;
import io.scalecube.services.transport.api.ClientTransportFactory;
import io.scalecube.services.transport.api.ServiceMessageCodec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** RSocket Transport Layer as Scalecube Transport. */
public class RSocketScalecubeClientTransport implements ClientTransportFactory {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(RSocketScalecubeClientTransport.class);

  private final ThreadLocal<Map<Address, Mono<RSocket>>> rsockets =
      ThreadLocal.withInitial(ConcurrentHashMap::new);

  private final ServiceMessageCodec codec;
  private final RSocketClientTransportFactory transportFactory;

  public RSocketScalecubeClientTransport(
      RSocketClientTransportFactory transportFactory, ServiceMessageCodec codec) {
    this.codec = codec;
    this.transportFactory = transportFactory;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientChannel create(Address address) {
    final Map<Address, Mono<RSocket>> monoMap = rsockets.get(); // keep reference for threadsafety
    Mono<RSocket> rsocket =
        monoMap.computeIfAbsent(address, address1 -> connect(address1, monoMap));
    return new RSocketClientChannel(rsocket, codec);
  }

  private Mono<RSocket> connect(Address address, Map<Address, Mono<RSocket>> monoMap) {
    Mono<RSocket> rsocketMono =
        RSocketFactory.connect()
            .frameDecoder(
                frame ->
                    ByteBufPayload.create(
                        frame.sliceData().retain(), frame.sliceMetadata().retain()))
            .errorConsumer(
                th -> LOGGER.warn("Exception occurred at rsocket client transport: " + th))
            .transport(() -> transportFactory.createClient(address))
            .start();

    return rsocketMono
        .doOnSuccess(
            rsocket -> {
              LOGGER.info("Connected successfully on {}", address);
              // setup shutdown hook
              rsocket
                  .onClose()
                  .doOnTerminate(
                      () -> {
                        monoMap.remove(address);
                        LOGGER.info("Connection closed on {}", address);
                      })
                  .subscribe(null, th -> LOGGER.warn("Exception on closing rsocket", th));
            })
        .doOnError(
            throwable -> {
              LOGGER.warn("Connect failed on {}, cause: {}", address, throwable);
              monoMap.remove(address);
            })
        .cache();
  }
}
