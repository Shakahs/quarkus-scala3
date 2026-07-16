import { kebabCase } from "lodash";

const marker = kebabCase("mvnpm lodash loaded");
const scalaJsResource = "/scala-js/scala-js.js";
console.log(JSON.stringify({ marker, scalaJsResource }));
