import ChunkInspector from './ChunkInspector.jsx';
import { withGuard } from './Guard.jsx';

/** 검색 점검은 담당자(GM) 전용이다. */
export default withGuard(ChunkInspector, { admin: true });
