import Chat from './Chat.jsx';
import { withGuard } from './Guard.jsx';

/** 로그인한 사용자만 상담 화면을 본다. */
export default withGuard(Chat);
