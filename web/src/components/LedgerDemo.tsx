import { useId, useState } from 'react'
import { formatCents } from '../lib/money'

const DEBIT = 250_00

/** A regra central do banco, na mão do visitante: se os dois lados não batem, nada é gravado. */
export function LedgerDemo() {
  const id = useId()
  const [credit, setCredit] = useState(DEBIT)
  const difference = credit - DEBIT
  const balanced = difference === 0

  return (
    <div className="demo" data-balanced={balanced}>
      <div className="demo-sides">
        <div>
          <span>Sai da conta de origem</span>
          <strong>{formatCents(DEBIT)}</strong>
        </div>
        <div>
          <span>Entra na conta de destino</span>
          <strong>{formatCents(credit)}</strong>
        </div>
      </div>
      <label htmlFor={id}>Mude o valor que entra</label>
      <input
        id={id}
        type="range"
        min={200_00}
        max={300_00}
        step={10_00}
        value={credit}
        onChange={(event) => setCredit(Number(event.target.value))}
        aria-valuetext={formatCents(credit)}
      />
      <output htmlFor={id}>
        {balanced
          ? 'Gravado. Os dois lados somam zero.'
          : `Recusado pelo banco de dados. ${difference > 0 ? 'Sobram' : 'Faltam'} ${formatCents(Math.abs(difference))}.`}
      </output>
    </div>
  )
}
