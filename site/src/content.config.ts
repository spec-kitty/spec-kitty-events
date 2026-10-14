import { defineCollection } from 'astro:content';
import {
  docKittyDocsSchema,
  docKittyDocsLoader,
} from '@spec-kitty/doc-toolkit/schema';

export const collections = {
  docs: defineCollection({
    // The content root is the repository's docs/ tree, one level up from site/.
    // `indexBasename` MUST match astro.config.mjs.
    loader: docKittyDocsLoader({ base: '../docs', indexBasename: ['README'] }),
    schema: docKittyDocsSchema(),
  }),
};
