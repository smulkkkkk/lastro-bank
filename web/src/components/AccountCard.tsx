import { useMemo } from 'react'
import { guilloche } from '../lib/guilloche'

export function AccountCard({ branch, number }: { branch: string; number: string }) {
  const paths = useMemo(() => guilloche(300, 105, 150), [])
  return (
    <div className="account-card">
      <svg className="engraving" viewBox="0 0 400 250" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
        {paths.map((d, i) => (
          <path key={i} d={d} className={i > 13 ? 'gold' : undefined} />
        ))}
      </svg>
      <span className="account-card-name">Lastro</span>
      <dl>
        <div>
          <dt>Agência</dt>
          <dd>{branch}</dd>
        </div>
        <div>
          <dt>Conta</dt>
          <dd>{number}</dd>
        </div>
      </dl>
    </div>
  )
}
