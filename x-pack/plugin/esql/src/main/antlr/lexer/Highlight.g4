/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
lexer grammar Highlight;

//
// HIGHLIGHT [prefix = "..."] query [ON field, ...] [WITH options]
//
// The query region and the ON fields are lexed in the shared EXPRESSION_MODE (like WHERE/RERANK) so the query can be a
// full-text function expression (MATCH(...), QSTR(...), boolean combinations, the ':' operator) and not just a literal.
// EXPRESSION_MODE already provides ON, WITH, commas, parentheses and function-call lexing, so no dedicated mode is needed.
DEV_HIGHLIGHT : {this.isDevVersion()}? 'highlight' -> pushMode(EXPRESSION_MODE);
