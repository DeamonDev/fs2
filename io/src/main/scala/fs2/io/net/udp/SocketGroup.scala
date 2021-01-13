/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io
package net
package udp

import java.net.{InetSocketAddress, NetworkInterface, ProtocolFamily}
import java.nio.channels.{ClosedChannelException, DatagramChannel}

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all._

import com.comcast.ip4s._

trait SocketGroup[F[_]] {

  /** Provides a UDP Socket that, when run, will bind to the specified address.
    *
    * @param address              address to bind to; defaults to all interfaces
    * @param port                 port to bind to; defaults to an ephemeral port
    * @param options              socket options to apply to the underlying socket
    * @param protocolFamily       protocol family to use when opening the supporting `DatagramChannel`
    */
  def open(
      address: Option[Host] = None,
      port: Option[Port] = None,
      options: List[SocketOption] = Nil,
      protocolFamily: Option[ProtocolFamily] = None
  ): Resource[F, Socket[F]]
}

object SocketGroup {
  def forAsync[F[_]: Async]: Resource[F, SocketGroup[F]] =
    AsynchronousSocketGroup[F].map(asg => new AsyncSocketGroup(asg))

  private final class AsyncSocketGroup[F[_]: Async](
      asg: AsynchronousSocketGroup
  ) extends SocketGroup[F] {

    def open(
        address: Option[Host],
        port: Option[Port],
        options: List[SocketOption],
        protocolFamily: Option[ProtocolFamily]
    ): Resource[F, Socket[F]] =
      Resource.eval(address.traverse(_.resolve[F])).flatMap { addr =>
        val mkChannel = Async[F].delay {
          val channel = protocolFamily
            .map(pf => DatagramChannel.open(pf))
            .getOrElse(DatagramChannel.open())
          options.foreach(o => channel.setOption[o.Value](o.key, o.value))
          channel.bind(
            new InetSocketAddress(addr.map(_.toInetAddress).orNull, port.map(_.value).getOrElse(0))
          )
          channel
        }
        Resource(mkChannel.flatMap(ch => mkSocket(ch).map(s => s -> s.close)))
      }

    private def mkSocket(
        channel: DatagramChannel
    ): F[Socket[F]] =
      Async[F].delay {
        new Socket[F] {
          private val ctx = asg.register(channel)

          def localAddress: F[SocketAddress[IpAddress]] =
            Async[F].delay {
              val addr =
                Option(channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])
                  .getOrElse(throw new ClosedChannelException)
              SocketAddress.fromInetSocketAddress(addr)
            }

          def read: F[Packet] =
            Async[F].async[Packet] { cb =>
              Async[F].delay {
                val cancel = asg.read(ctx, result => cb(result))
                Some(Async[F].delay((cancel())))
              }
            }

          def reads: Stream[F, Packet] =
            Stream.repeatEval(read)

          def write(packet: Packet): F[Unit] =
            Async[F].async[Unit] { cb =>
              Async[F].delay {
                val cancel = asg.write(ctx, packet, t => cb(t.toLeft(())))
                Some(Async[F].delay(cancel()))
              }
            }

          def writes: Pipe[F, Packet, INothing] =
            _.foreach(write)

          def close: F[Unit] = Async[F].delay(asg.close(ctx))

          def join(
              join: MulticastJoin[IpAddress],
              interface: NetworkInterface
          ): F[GroupMembership] =
            Async[F].delay {
              val membership = join.fold(
                j => channel.join(j.group.address.toInetAddress, interface),
                j => channel.join(j.group.address.toInetAddress, interface, j.source.toInetAddress)
              )
              new GroupMembership {
                def drop = Async[F].delay(membership.drop)
                def block(source: IpAddress) =
                  Async[F].delay { membership.block(source.toInetAddress); () }
                def unblock(source: IpAddress) =
                  Async[F].delay { membership.unblock(source.toInetAddress); () }
                override def toString = "GroupMembership"
              }
            }

          override def toString =
            s"Socket(${Option(
              channel.socket.getLocalSocketAddress
            ).getOrElse("<unbound>")})"
        }
      }
  }
}
