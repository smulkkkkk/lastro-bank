/**
 * Peixe-beta translúcido, desenhado em WebGL puro. Tudo é calculado na placa de vídeo a partir de
 * grades (s, t): o corpo é um tubo em volta da espinha, e cada nadadeira é um leque de fios de seda.
 */

const VERTEX = `
precision highp float;
attribute vec2 a_st;
uniform float u_time, u_aspect, u_part, u_scale;
uniform vec2 u_pointer, u_center;
varying vec2 v_st;
varying vec3 v_normal;
varying float v_fold;

const float PI = 3.14159265;

vec3 spine(float u) {
  return vec3(mix(-0.55, 0.36, u),
              0.035 * sin(2.4 * u - u_time * 1.0),
              (0.015 + 0.12 * u * u) * sin(3.0 * u - u_time * 1.6));
}

float radius(float u) {
  return 0.42 * pow(u + 0.004, 0.62) * pow(1.0 - 0.9 * u, 1.25);
}

vec3 body(vec2 st) {
  float a = st.x * 2.0 * PI, r = radius(st.y);
  return spine(st.y) + vec3(0.0, r * sin(a), r * 0.55 * cos(a));
}

/* Raiz, direção e comprimento de cada fio; as ondas correm da raiz para a ponta. */
vec3 silk(vec3 base, float angle, float len, float s, float t, float amount) {
  vec3 p = base + vec3(cos(angle), sin(angle), 0.0) * len * t;
  float phase = s * 15.0 + u_time * 0.9 - t * 4.2;
  v_fold = 0.5 + 0.5 * sin(phase) * cos(s * 31.0 - t * 2.0);
  p.z += amount * t * (0.16 * sin(phase) + 0.30 * sin(2.6 - u_time * 1.6 - t * 2.4));
  p.y += amount * (0.055 * t * sin(s * 9.0 - t * 5.0 + u_time * 0.8) - 0.17 * t * t);
  p.x += amount * 0.05 * t * sin(s * 21.0 + u_time * 0.7 + t * 3.0);
  return p;
}

vec3 fin(float s, float t) {
  if (u_part < 1.5) {
    float angle = mix(-1.22, 1.16, s);
    float len = 0.80 + 0.14 * sin(s * 7.0) + 0.10 * sin(s * 17.0);
    return silk(spine(1.0) + vec3(-0.02, mix(-0.024, 0.024, s), 0.0), angle, len, s, t, 1.0);
  }
  if (u_part < 2.5) {
    float u = mix(0.30, 0.82, s);
    return silk(spine(u) + vec3(0.0, radius(u) * 0.92, 0.0), mix(1.35, 0.42, s),
                0.20 + 0.36 * sin(PI * (0.12 + 0.7 * s)), s, t, 0.7);
  }
  if (u_part < 3.5) {
    float u = mix(0.36, 0.97, s);
    return silk(spine(u) - vec3(0.0, radius(u) * 0.92, 0.0), mix(-1.45, -0.62, s),
                0.30 + 0.48 * s + 0.08 * sin(s * 19.0), s, t, 0.9);
  }
  if (u_part < 4.5) {
    float side = s < 0.5 ? -1.0 : 1.0;
    float w = fract(s * 2.0) - 0.5;
    vec3 p = silk(spine(0.27) - vec3(0.0, radius(0.27) * 0.9, side * 0.035), -1.15 + side * 0.12, 0.82, side * 0.2, t, 1.0);
    return p + vec3(w * 0.07 * (1.0 - 0.5 * t), w * 0.02, 0.03 * side * t);
  }
  float flap = 0.35 * sin(u_time * 2.2);
  float angle = mix(-0.75, 0.45, s);
  return spine(0.30) + vec3(0.0, -0.02, 0.075)
       + vec3(cos(angle), sin(angle) - 0.25, 0.55 + flap) * 0.22 * t * (0.7 + 0.3 * sin(PI * s));
}

mat2 turn(float a) { return mat2(cos(a), sin(a), -sin(a), cos(a)); }

vec3 pose(vec3 p) {
  p.x -= 0.30;
  p.xz = turn(0.42 + u_pointer.x * 0.22 + 0.06 * sin(u_time * 0.4)) * p.xz;
  p.yz = turn(-0.16 + u_pointer.y * 0.16) * p.yz;
  p.xy = turn(-0.40 + 0.04 * sin(u_time * 0.5)) * p.xy;
  p.y += 0.025 * sin(u_time * 0.7);
  return p;
}

void main() {
  v_st = a_st;
  v_fold = 0.5;
  v_normal = vec3(0.0, 0.0, 1.0);
  vec3 p;
  if (u_part < 0.5) {
    vec3 here = pose(body(a_st));
    vec3 ds = pose(body(a_st + vec2(0.004, 0.0))) - here;
    vec3 dt = pose(body(a_st + vec2(0.0, 0.004))) - here;
    v_normal = normalize(cross(ds, dt) + vec3(0.0, 0.0, 1e-6));
    p = here;
  } else if (u_part < 5.5) {
    p = pose(fin(a_st.x, a_st.y));
  } else {
    /* Partículas em suspensão. */
    float depth = fract(a_st.x * 7.31);
    p = vec3((fract(a_st.x * 13.7 + u_time * 0.006 * (0.4 + depth)) - 0.5) * 3.6,
             (fract(a_st.y * 5.9 + u_time * 0.011 * (0.4 + depth)) - 0.5) * 2.4, depth - 0.5);
    gl_PointSize = 1.5 + 5.0 * depth * depth;
    v_fold = depth;
  }
  float w = 1.0 - p.z * 0.35;
  vec2 xy = p.xy * u_scale / w;
  if (u_part < 5.5) xy += u_center;
  gl_Position = vec4(xy.x / u_aspect, xy.y, 0.0, 1.0);
}`

const FRAGMENT = `
precision highp float;
uniform float u_time, u_part, u_line;
uniform vec3 u_deep, u_pearl, u_ember;
varying vec2 v_st;
varying vec3 v_normal;
varying float v_fold;

const float PI = 3.14159265;

void main() {
  vec3 color;
  float alpha;
  if (u_part < 0.5) {
    vec3 n = normalize(v_normal);
    n *= n.z < 0.0 ? -1.0 : 1.0;
    float facing = abs(n.z);
    float rim = pow(1.0 - facing, 2.2);
    float light = 0.5 + 0.5 * dot(n, normalize(vec3(-0.35, 0.8, 0.5)));
    float u = v_st.y;
    color = mix(u_deep, u_pearl, 0.12 + 0.62 * light * light + 0.25 * rim);

    /* Escamas. */
    vec2 g = vec2(u * 40.0, v_st.x * 34.0);
    g.x += 0.5 * mod(floor(g.y), 2.0);
    vec2 c = fract(g) - 0.5;
    float scale = smoothstep(0.10, 0.0, abs(length(c - vec2(-0.14, 0.0)) - 0.42)) * step(-0.14, c.x);
    color += (u_pearl - u_deep) * scale * 0.20 * smoothstep(0.20, 0.34, u) * facing;

    /* Brasa por dentro, com um pulso lento. */
    float heart = exp(-pow((u - 0.36) / 0.17, 2.0)) * (0.78 + 0.22 * sin(u_time * 1.5));
    float core = exp(-pow((u - 0.66) / 0.30, 2.0)) * 0.35;
    color = mix(color, u_ember, clamp((heart + core) * pow(facing, 1.2) * 0.92, 0.0, 1.0));
    alpha = 0.80 + 0.18 * rim;

    /* Guelra e olho. */
    float around = fract(v_st.x + 0.5) - 0.5;
    float gill = smoothstep(0.012, 0.0, abs(u - 0.235 - 0.35 * around * around)) * smoothstep(0.17, 0.10, abs(around));
    color = mix(color, u_deep, gill * 0.55);
    float eye = length(vec2((u - 0.115) * 0.91, (around - 0.055) * 0.46));
    color = mix(color, u_pearl, smoothstep(0.030, 0.026, eye) * 0.9);
    color = mix(color, vec3(0.03, 0.03, 0.04), smoothstep(0.021, 0.018, eye));
    color = mix(color, vec3(1.0), smoothstep(0.007, 0.004, length(vec2((u - 0.109) * 0.91, (around - 0.068) * 0.46))));
  } else if (u_part < 5.5) {
    float t = v_st.y;
    float fade = pow(1.0 - t, 0.75) * smoothstep(0.0, 0.05, t + 0.02);
    float across = u_part > 3.5 && u_part < 4.5 ? fract(v_st.x * 2.0) : v_st.x;
    if (u_part > 4.5) fade *= 0.6 * smoothstep(0.0, 0.45, t);
    fade *= smoothstep(0.0, 0.10, across) * smoothstep(1.0, 0.90, across);
    color = mix(u_ember, mix(u_deep, u_pearl, 0.25 + 0.75 * v_fold), smoothstep(0.0, 0.55, t));
    color = mix(color, u_pearl, 0.35 * v_fold);
    alpha = u_line > 0.5 ? fade * (0.10 + 0.30 * v_fold) : fade * (0.06 + 0.30 * v_fold * v_fold);
  } else {
    float d = length(gl_PointCoord - 0.5);
    color = mix(u_pearl, u_ember, step(0.86, v_fold));
    alpha = smoothstep(0.5, 0.0, d) * 0.45 * v_fold;
  }
  gl_FragColor = vec4(color * alpha, alpha);
}`

type Rgb = [number, number, number]

export interface BettaColors {
  deep: Rgb
  pearl: Rgb
  ember: Rgb
}

export interface BettaFrame {
  time: number
  width: number
  height: number
  pointer: [number, number]
  colors: BettaColors
}

export interface Betta {
  draw: (frame: BettaFrame) => void
  destroy: () => void
}

interface Grid {
  vertices: WebGLBuffer
  surface: WebGLBuffer
  surfaceCount: number
  strands: WebGLBuffer
  strandCount: number
}

/** Pontos, triângulos (a seda) e linhas ao longo de t (os fios) de uma grade. */
export function gridData(across: number, along: number) {
  const vertices = new Float32Array((across + 1) * (along + 1) * 2)
  const surface: number[] = []
  const strands: number[] = []
  let v = 0
  for (let i = 0; i <= across; i++) {
    for (let j = 0; j <= along; j++) {
      vertices[v++] = i / across
      vertices[v++] = j / along
      const here = i * (along + 1) + j
      if (j < along) strands.push(here, here + 1)
      if (i < across && j < along) {
        const next = here + along + 1
        surface.push(here, next, here + 1, next, next + 1, here + 1)
      }
    }
  }
  return { vertices, surface: new Uint16Array(surface), strands: new Uint16Array(strands) }
}

// De trás para a frente; a última nadadeira fica sobre o corpo.
const FINS = [
  { part: 4, across: 35, along: 48 },
  { part: 1, across: 190, along: 44 },
  { part: 3, across: 130, along: 36 },
  { part: 2, across: 100, along: 32 },
  { part: 5, across: 30, along: 16 },
]
const MOTES = 110

export function createBetta(canvas: HTMLCanvasElement): Betta | null {
  const gl = canvas.getContext('webgl', { alpha: true, antialias: true, powerPreference: 'low-power' })
  if (!gl) return null

  const compile = (type: number, source: string) => {
    const shader = gl.createShader(type)!
    gl.shaderSource(shader, source)
    gl.compileShader(shader)
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS) && !gl.isContextLost()) {
      throw new Error(gl.getShaderInfoLog(shader) ?? 'shader inválido')
    }
    return shader
  }
  const program = gl.createProgram()!
  gl.attachShader(program, compile(gl.VERTEX_SHADER, VERTEX))
  gl.attachShader(program, compile(gl.FRAGMENT_SHADER, FRAGMENT))
  gl.linkProgram(program)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return null
  gl.useProgram(program)

  const uniform = (name: string) => gl.getUniformLocation(program, name)
  const at = {
    time: uniform('u_time'), aspect: uniform('u_aspect'), part: uniform('u_part'), scale: uniform('u_scale'),
    pointer: uniform('u_pointer'), center: uniform('u_center'), line: uniform('u_line'),
    deep: uniform('u_deep'), pearl: uniform('u_pearl'), ember: uniform('u_ember'),
  }
  const st = gl.getAttribLocation(program, 'a_st')
  gl.enableVertexAttribArray(st)

  const upload = (target: number, data: Float32Array | Uint16Array) => {
    const buffer = gl.createBuffer()!
    gl.bindBuffer(target, buffer)
    gl.bufferData(target, data, gl.STATIC_DRAW)
    return buffer
  }
  const grid = (across: number, along: number): Grid => {
    const data = gridData(across, along)
    return {
      vertices: upload(gl.ARRAY_BUFFER, data.vertices),
      surface: upload(gl.ELEMENT_ARRAY_BUFFER, data.surface),
      surfaceCount: data.surface.length,
      strands: upload(gl.ELEMENT_ARRAY_BUFFER, data.strands),
      strandCount: data.strands.length,
    }
  }
  const bodyGrid = grid(48, 80)
  const fins = FINS.map((item) => ({ part: item.part, grid: grid(item.across, item.along) }))
  const motes = upload(gl.ARRAY_BUFFER, Float32Array.from({ length: MOTES * 2 }, (_, i) => (i * 0.61803) % 1))

  const use = (buffer: WebGLBuffer) => {
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.vertexAttribPointer(st, 2, gl.FLOAT, false, 0, 0)
  }
  const elements = (mode: number, buffer: WebGLBuffer, count: number) => {
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, buffer)
    gl.drawElements(mode, count, gl.UNSIGNED_SHORT, 0)
  }
  const drawFin = (fin: { part: number; grid: Grid }) => {
    gl.uniform1f(at.part, fin.part)
    use(fin.grid.vertices)
    gl.uniform1f(at.line, 0)
    elements(gl.TRIANGLES, fin.grid.surface, fin.grid.surfaceCount)
    gl.uniform1f(at.line, 1)
    elements(gl.LINES, fin.grid.strands, fin.grid.strandCount)
  }

  gl.enable(gl.BLEND)
  gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
  gl.cullFace(gl.FRONT)

  return {
    draw({ time, width, height, pointer, colors }) {
      if (gl.isContextLost()) return
      const aspect = width / height
      gl.viewport(0, 0, width, height)
      gl.clearColor(0, 0, 0, 0)
      gl.clear(gl.COLOR_BUFFER_BIT)
      gl.uniform1f(at.time, time)
      gl.uniform1f(at.aspect, aspect)
      gl.uniform1f(at.scale, Math.min(1.08, aspect * 1.02))
      gl.uniform2f(at.center, 0, aspect < 1 ? 0.1 : 0.09)
      gl.uniform2f(at.pointer, pointer[0], pointer[1])
      gl.uniform3fv(at.deep, colors.deep)
      gl.uniform3fv(at.pearl, colors.pearl)
      gl.uniform3fv(at.ember, colors.ember)

      gl.uniform1f(at.part, 6)
      use(motes)
      gl.drawArrays(gl.POINTS, 0, MOTES)

      fins.slice(0, -1).forEach(drawFin)

      // Só a metade virada para a câmera, para o corpo não se misturar consigo mesmo.
      gl.enable(gl.CULL_FACE)
      gl.uniform1f(at.part, 0)
      use(bodyGrid.vertices)
      elements(gl.TRIANGLES, bodyGrid.surface, bodyGrid.surfaceCount)
      gl.disable(gl.CULL_FACE)

      drawFin(fins[fins.length - 1]!)
    },
    destroy() {
      gl.getExtension('WEBGL_lose_context')?.loseContext()
    },
  }
}
