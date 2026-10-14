import { agentPageRoute } from '@spec-kitty/doc-toolkit/routes';

const route = agentPageRoute();
export const getStaticPaths = route.getStaticPaths;
export const GET = route.GET;
