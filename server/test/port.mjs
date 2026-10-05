// 取一个当前空闲的 TCP 端口后立刻释放（黑盒临时实例用，避免与常驻 18081/8080 撞端口）
import net from 'node:net';

const s = net.createServer();
s.listen(0, '127.0.0.1', () => {
  console.log(s.address().port);
  s.close();
});
