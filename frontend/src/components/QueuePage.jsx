import TicketQueue from './TicketQueue.jsx';
import { withGuard } from './Guard.jsx';

/** 승인 대기 큐는 담당자(GM) 전용이다. */
export default withGuard(TicketQueue, { admin: true });
