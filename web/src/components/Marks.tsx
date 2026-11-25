/** Símbolo: moeda com cauda. */
export function FishMark() {
  return (
    <svg className="fish-mark" viewBox="1 3 42 34" aria-hidden="true">
      <path className="fish-tail" d="M33 20c3-5 6-7 9-7 0 4-1 6-3 7 2 1 3 3 3 7-3 0-6-2-9-7z" />
      <path
        className="fish-body"
        fillRule="evenodd"
        d="M18 4a16 16 0 1 1 0 32 16 16 0 0 1 0-32zm-7 8a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"
      />
      <path className="fish-gill" d="M21 11c4 5.500 4 12.500 0 18" />
    </svg>
  )
}

/** Nome desenhado à mão. O "O" final é o peixe: ganha uma cauda de brasa. */
export function Wordmark() {
  return (
    <svg className="wordmark" viewBox="-5 -5 382 50" role="img" aria-label="Lastro">
      <g className="wordmark-strokes">
        <path d="M0 0v30l10 10h34" />
        <path d="M62 40V14L76 0h16l14 14v26" />
        <path d="M168 0h-34l-10 10 10 10h24l10 10-10 10h-34" />
        <path d="M186 0h46M209 12v28" />
        <path d="M250 40V0h34l10 11-10 11h-22M278 28l16 12" />
        <path d="M326 0h16l14 14v12l-14 14h-16l-14-14V14z" />
      </g>
      <circle className="wordmark-dot" cx="84" cy="25" r="4.500" />
      <path className="wordmark-tail" d="M363 20l13-10v20z" />
    </svg>
  )
}
